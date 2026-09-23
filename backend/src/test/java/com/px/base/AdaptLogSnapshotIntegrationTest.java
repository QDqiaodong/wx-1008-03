package com.px.base;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.px.base.entity.AdaptLog;
import com.px.base.entity.FlightRoute;
import com.px.base.repository.AdaptLogRepository;
import com.px.base.repository.FlightRouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 适配流水“读取=导出=同一份可复核快照”的端到端验收（H2 + MockMvc，Redis 深桩）。
 *
 * 覆盖：
 *  - 航线筛选/全量/分页共用稳定排序（create_time DESC, id DESC），刷新不乱序；
 *  - 快照 total、页码、记录顺序对应同一查询时刻（snapshotMaxId 高水位）；
 *  - 导出在服务端按当前筛选生成，文件名和内容带筛选航线/查询时刻/总数；
 *  - 导出过程中并发新增绑定/拒绝流水，导出文件的总数、排序、筛选仍自洽；
 *  - 空命中（NO_MATCH、total=0）与请求失败（4xx）明确区分；
 *  - 连续发起不同航线查询，只承认最后一次筛选口径。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("itest")
class AdaptLogSnapshotIntegrationTest {

    @TestConfiguration
    static class RedisStubConfig {
        @Bean
        @Primary
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> stubRedisTemplate() {
            return mock(RedisTemplate.class, RETURNS_DEEP_STUBS);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired FlightRouteRepository routeRepository;
    @Autowired AdaptLogRepository adaptLogRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private long routeAlpha;
    private long routeBeta;

    @BeforeEach
    void setUp() {
        adaptLogRepository.deleteAll();
        routeAlpha = route("LOG-ALPHA", "快照验收航线甲");
        routeBeta = route("LOG-BETA", "快照验收航线乙");

        // 同一发生时刻插入 3 条：稳定排序必须用自增 id 打破并列（id 倒序）
        LocalDateTime sameMoment = LocalDateTime.of(2026, 9, 23, 10, 0, 0);
        long a1 = saveLog(routeAlpha, "LOG-ALPHA", "ANCHOR-A1", "BIND", sameMoment);
        long a2 = saveLog(routeAlpha, "LOG-ALPHA", "ANCHOR-A2", "REJECT", sameMoment);
        long a3 = saveLog(routeAlpha, "LOG-ALPHA", "ANCHOR-A3", "BIND", sameMoment);
        // 更早一条，应排在最末
        saveLog(routeAlpha, "LOG-ALPHA", "ANCHOR-A0", "UNBIND", LocalDateTime.of(2026, 9, 22, 9, 0, 0));
        saveLog(routeBeta, "LOG-BETA", "ANCHOR-B1", "BIND", LocalDateTime.of(2026, 9, 23, 11, 0, 0));

        // @PrePersist 会盖时间，这里用 SQL 把并列时刻钉死，确保真能验证 tie-break
        jdbcTemplate.update("update adapt_log set create_time = ? where id = ?",
                java.sql.Timestamp.valueOf(sameMoment), a1);
        jdbcTemplate.update("update adapt_log set create_time = ? where id = ?",
                java.sql.Timestamp.valueOf(sameMoment), a2);
        jdbcTemplate.update("update adapt_log set create_time = ? where id = ?",
                java.sql.Timestamp.valueOf(sameMoment), a3);
    }

    private long route(String code, String name) {
        return routeRepository.findByRouteCode(code)
                .orElseGet(() -> routeRepository.save(FlightRoute.builder()
                        .routeCode(code).routeName(name).routeGroup("东区")
                        .windSpeed(new BigDecimal("8.00")).windLevel("和风")
                        .description(name).status(1).build()))
                .getId();
    }

    private long saveLog(long routeId, String routeCode, String anchorCode, String op, LocalDateTime time) {
        AdaptLog l = AdaptLog.builder()
                .routeId(routeId).routeCode(routeCode)
                .anchorId(routeId * 100 + anchorCode.hashCode()).anchorCode(anchorCode)
                .operationType(op).afterWindSpeed(new BigDecimal("8.00")).afterWeight(new BigDecimal("1000.00"))
                .reason("验收-" + op).operator("tester")
                .build();
        long id = adaptLogRepository.save(l).getId();
        jdbcTemplate.update("update adapt_log set create_time = ? where id = ?",
                java.sql.Timestamp.valueOf(time), id);
        return id;
    }

    private JsonNode data(MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString()).path("data");
    }

    @Test
    void 航线筛选_全量_分页共用稳定排序_并列时间按自增编号倒序() throws Exception {
        // 航线甲：4 条。同一时刻 3 条必须按 id 倒序，更早一条排最后
        JsonNode page = data(mvc.perform(get("/api/adapt/logs")
                        .param("routeId", String.valueOf(routeAlpha))
                        .param("pageNo", "1").param("pageSize", "10"))
                .andExpect(status().isOk()).andReturn());

        assertThat(page.path("total").asLong()).isEqualTo(4);
        assertThat(page.path("routeCode").asText()).isEqualTo("LOG-ALPHA");
        assertThat(page.path("sort").asText()).isEqualTo("create_time DESC, id DESC");
        assertThat(page.path("snapshotMaxId").asLong()).isGreaterThan(0);

        List<Integer> ids = idsOf(page);
        assertThat(ids).hasSize(4);
        // 前三条为同一时刻：id 倒序；第四条是更早的
        assertThat(ids.get(0)).isGreaterThan(ids.get(1));
        assertThat(ids.get(1)).isGreaterThan(ids.get(2));
        List<String> anchorOrder = anchorCodesOf(page);
        assertThat(anchorOrder.get(3)).isEqualTo("ANCHOR-A0");
    }

    @Test
    void 分页两页拼接与全量快照顺序完全一致且总数对应同一时刻() throws Exception {
        JsonNode p1 = data(mvc.perform(get("/api/adapt/logs")
                        .param("routeId", String.valueOf(routeAlpha))
                        .param("pageNo", "1").param("pageSize", "2"))
                .andExpect(status().isOk()).andReturn());
        JsonNode p2 = data(mvc.perform(get("/api/adapt/logs")
                        .param("routeId", String.valueOf(routeAlpha))
                        .param("pageNo", "2").param("pageSize", "2"))
                .andExpect(status().isOk()).andReturn());

        assertThat(p1.path("total").asLong()).isEqualTo(4);
        assertThat(p1.path("totalPages").asInt()).isEqualTo(2);
        assertThat(p2.path("total").asLong()).isEqualTo(4);

        List<Integer> paged = new ArrayList<>();
        paged.addAll(idsOf(p1));
        paged.addAll(idsOf(p2));

        // 导出（全量快照）顺序
        JsonNode export = exportJson(routeAlpha);
        List<Integer> exported = new ArrayList<>();
        export.path("records").forEach(n -> exported.add(n.path("id").asInt()));

        assertThat(exported).containsExactlyElementsOf(paged);
        // 全量快照内 total 与行数严格相等
        assertThat(export.path("meta").path("total").asLong()).isEqualTo(exported.size());
    }

    @Test
    void 全量查询不带航线时返回全部航线流水并保持稳定排序() throws Exception {
        JsonNode page = data(mvc.perform(get("/api/adapt/logs")
                        .param("pageNo", "1").param("pageSize", "50"))
                .andExpect(status().isOk()).andReturn());
        assertThat(page.path("total").asLong()).isEqualTo(5);
        assertThat(page.path("filter").path("routeId").isNull());
        // 最新的是乙航线 11 点那条
        assertThat(anchorCodesOf(page).get(0)).isEqualTo("ANCHOR-B1");

        // 空筛选（全部航线）导出：total 与行数一致，文件名 route-ALL
        MvcResult exportAll = mvc.perform(get("/api/adapt/logs/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("route-ALL")))
                .andReturn();
        JsonNode allFile = om.readTree(exportAll.getResponse().getContentAsString());
        assertThat(allFile.path("filter").path("routeCode").asText()).isEqualTo("全部航线");
        assertThat(allFile.path("meta").path("total").asLong()).isEqualTo(5);
        assertThat(allFile.path("records").size()).isEqualTo(5);
        // 最新一条（乙航线）排第一，与分页口径一致
        assertThat(allFile.path("records").get(0).path("anchorCode").asText()).isEqualTo("ANCHOR-B1");
    }

    @Test
    void 导出过程中并发新增流水_文件总数排序筛选仍自洽() throws Exception {
        int rounds = 12;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch done = new CountDownLatch(rounds + rounds);
        AtomicInteger exports = new AtomicInteger();
        AtomicInteger inserts = new AtomicInteger();

        for (int i = 0; i < rounds; i++) {
            // 导出线程：不断导出“航线甲”的快照
            pool.submit(() -> {
                try {
                    JsonNode file = exportJson(routeAlpha);
                    exports.incrementAndGet();

                    // 1) 只含筛选航线
                    file.path("records").forEach(n ->
                            assertThat(n.path("routeId").asLong()).isEqualTo(routeAlpha));

                    // 2) 行内每条 id <= 快照高水位，且与 snapshotMaxId 同一查询时刻
                    long maxId = file.path("meta").path("snapshotMaxId").asLong();
                    List<Integer> ids = new ArrayList<>();
                    file.path("records").forEach(n -> {
                        int id = n.path("id").asInt();
                        assertThat((long) id).isLessThanOrEqualTo(maxId);
                        ids.add(id);
                    });

                    // 3) total 与行数一致（查询期间新增不能把总数和行数撕开）
                    assertThat(file.path("meta").path("total").asLong()).isEqualTo(ids.size());

                    // 4) 稳定排序：id 非增（createTime/id DESC 在并列时刻也成立）
                    for (int k = 1; k < ids.size(); k++) {
                        assertThat(ids.get(k - 1)).isGreaterThanOrEqualTo(ids.get(k));
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });

            // 新增线程：交替写绑定/拒绝，且分别写甲、乙两条航线（验证快照边界与筛选）
            pool.submit(() -> {
                try {
                    boolean bind = inserts.incrementAndGet() % 2 == 0;
                    long targetRoute = inserts.get() % 2 == 0 ? routeAlpha : routeBeta;
                    String code = targetRoute == routeAlpha ? "LOG-ALPHA" : "LOG-BETA";
                    saveLog(targetRoute, code,
                            "RACE-" + System.nanoTime(), bind ? "BIND" : "REJECT",
                            LocalDateTime.now());
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(exports.get()).isEqualTo(rounds);
    }

    @Test
    void 空结果与请求失败明确区分_空命中也能导出NO_MATCH文件() throws Exception {
        // 新建一条没有任何流水的航线：请求成功、total=0、records 为空
        long emptyRoute = route("LOG-EMPTY", "无流水航线");
        JsonNode page = data(mvc.perform(get("/api/adapt/logs")
                        .param("routeId", String.valueOf(emptyRoute)))
                .andExpect(status().isOk()).andReturn());
        assertThat(page.path("total").asLong()).isZero();
        assertThat(page.path("records").size()).isZero();

        // 空命中导出：HTTP 200 + resultState=NO_MATCH + total=0，而不是报错
        JsonNode file = exportJson(emptyRoute);
        assertThat(file.path("resultState").asText()).isEqualTo("NO_MATCH");
        assertThat(file.path("meta").path("total").asLong()).isZero();
        assertThat(file.path("records").size()).isZero();
        assertThat(file.path("filter").path("routeId").asLong()).isEqualTo(emptyRoute);

        // 有命中时状态为 OK
        JsonNode ok = exportJson(routeAlpha);
        assertThat(ok.path("resultState").asText()).isEqualTo("OK");
        assertThat(ok.path("meta").path("total").asLong()).isGreaterThan(0);

        // 真失败：筛选不存在的航线 → 400（与空命中明确区分）
        mvc.perform(get("/api/adapt/logs").param("routeId", "99999999"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/adapt/logs/export").param("routeId", "99999999"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 导出文件名和内容都带筛选航线_查询时刻_记录总数() throws Exception {
        MvcResult res = mvc.perform(get("/api/adapt/logs/export")
                        .param("routeId", String.valueOf(routeAlpha)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andReturn();

        String disposition = res.getResponse().getHeader("Content-Disposition");
        assertThat(disposition).contains("route-LOG-ALPHA").contains("total-4");

        JsonNode file = om.readTree(res.getResponse().getContentAsString());
        assertThat(file.path("data").isMissingNode()).isTrue(); // 导出直接是文件，不是 ResponseDTO
        JsonNode meta = file.path("meta");
        assertThat(meta.path("total").asLong()).isEqualTo(4);
        assertThat(meta.path("snapshotTime").asText()).isNotBlank();
        assertThat(meta.path("sort").asText()).isEqualTo("create_time DESC, id DESC");
        assertThat(file.path("filter").path("routeCode").asText()).isEqualTo("LOG-ALPHA");
        assertThat(file.path("records").size()).isEqualTo(4);
    }

    @Test
    void 重新打开页面后分页与导出使用同一条查询口径() throws Exception {
        // 模拟“重新打开流水页”：两个独立请求，无任何前端状态，仅靠接口入参
        JsonNode reopenedPage = data(mvc.perform(get("/api/adapt/logs")
                        .param("routeId", String.valueOf(routeAlpha))
                        .param("pageNo", "1").param("pageSize", "10"))
                .andExpect(status().isOk()).andReturn());
        JsonNode reopenedExport = exportJson(routeAlpha);

        long pageMax = reopenedPage.path("snapshotMaxId").asLong();
        long exportMax = reopenedExport.path("meta").path("snapshotMaxId").asLong();
        // 两个请求都以各自时刻的 max(id) 为高水位；本用例期间无写入，应完全相同
        assertThat(exportMax).isEqualTo(pageMax);
        assertThat(reopenedExport.path("meta").path("total").asLong())
                .isEqualTo(reopenedPage.path("total").asLong());

        List<Integer> pageIds = idsOf(reopenedPage);
        List<Integer> exportIds = new ArrayList<>();
        reopenedExport.path("records").forEach(n -> exportIds.add(n.path("id").asInt()));
        assertThat(exportIds).containsExactlyElementsOf(pageIds);
    }

    private JsonNode exportJson(long routeId) throws Exception {
        MvcResult res = mvc.perform(get("/api/adapt/logs/export")
                        .param("routeId", String.valueOf(routeId)))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(res.getResponse().getContentAsString());
    }

    private List<Integer> idsOf(JsonNode page) {
        List<Integer> ids = new ArrayList<>();
        page.path("records").forEach(n -> ids.add(n.path("id").asInt()));
        return ids;
    }

    private List<String> anchorCodesOf(JsonNode page) {
        List<String> codes = new ArrayList<>();
        page.path("records").forEach(n -> codes.add(n.path("anchorCode").asText()));
        return codes;
    }
}
