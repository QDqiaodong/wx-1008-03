package com.px.base;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.px.base.entity.FlightRoute;
import com.px.base.entity.FlightWatch;
import com.px.base.entity.GroundStaff;
import com.px.base.repository.FlightRouteRepository;
import com.px.base.repository.FlightWatchRepository;
import com.px.base.repository.GroundCertRepository;
import com.px.base.repository.GroundStaffRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 端到端验收（H2 + MockMvc；Redis 以深桩替身，不依赖外部进程）：
 * 串起人员/证书/值守/航线入口四个接口，覆盖需求列出的全部验收点。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("itest")
class GroundWatchIntegrationTest {

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
    @Autowired GroundStaffRepository staffRepository;
    @Autowired FlightRouteRepository routeRepository;
    @Autowired FlightWatchRepository watchRepository;
    @Autowired GroundCertRepository certRepository;

    private final DateTimeFormatter iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private JsonNode json(MvcResult r) throws Exception {
        JsonNode root = om.readTree(r.getResponse().getContentAsString());
        // 统一解包 ResponseDTO：业务断言都直接面向 data
        return root.has("data") ? root.path("data") : root;
    }

    private long staffId(String code) {
        return staffRepository.findByStaffCode(code).orElseThrow().getId();
    }

    private long routeId(String code) {
        return routeRepository.findByRouteCode(code).orElseThrow().getId();
    }

    @Test
    void 全链路验收() throws Exception {
        long zhao = staffId("S001");
        long qian = staffId("S002");
        long sun = staffId("S003");
        long li = staffId("S004");
        long rStrong = routeId("R-STRONG");
        long rGale = routeId("R-GALE");

        LocalDate tomorrow = LocalDate.now().plusDays(1);

        /* 0) 口径元数据：跨午夜按起飞时刻，并给出不采用区间口径的原因 */
        MvcResult metaRes = mvc.perform(get("/api/ground/meta")).andExpect(status().isOk()).andReturn();
        JsonNode meta = json(metaRes);
        assertThat(meta.path("policy").asText()).contains("预计起飞时刻");
        assertThat(meta.path("rejectedReason").asText()).contains("不采用");

        /* 1) 普通值班员直接吊销证书 → 403，数据不变 */
        mvc.perform(post("/api/ground/cert/{id}/revoke", certIdOfZhaoAll())
                        .header("X-Staff-Id", qian)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"试试\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("仅安全主管")));
        // 证书仍然有效
        mvc.perform(get("/api/ground/staff").param("takeoff", tomorrow.atTime(9, 0).format(iso)))
                .andExpect(status().isOk());

        /* 2) 同一人被安排为两个职责 → 明确拒绝（种子明天 R-STRONG 已占用，换 R-GALE 做同一人校验） */
        String samePersonBody = om.writeValueAsString(java.util.Map.of(
                "routeId", rGale, "operatorId", zhao, "reviewerId", zhao,
                "plannedTakeoff", tomorrow.atTime(20, 0).format(iso)));
        mvc.perform(post("/api/watch").header("X-Staff-Id", sun)
                        .contentType(MediaType.APPLICATION_JSON).content(samePersonBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不能为同一人")));

        /* 3) 种子：明天 R-STRONG（强风/西区）草拟值守，操作员钱、复核员赵。
              钱的证书只覆盖微风轻风+东区 → 必须逐项列出风级与区域缺口。 */
        MvcResult listRes = mvc.perform(get("/api/watch").param("routeId", String.valueOf(rStrong)))
                .andExpect(status().isOk()).andReturn();
        JsonNode watches = json(listRes);
        assertThat(watches.isArray()).isTrue();
        JsonNode futureDraft = findWatch(watches, tomorrow, "DRAFT");
        long strongWatchId = futureDraft.path("id").asLong();
        assertThat(futureDraft.path("operatorQualification").path("qualified").asBoolean()).isFalse();
        String gapText = futureDraft.path("operatorQualification").path("detailMessages").toString();
        assertThat(gapText).contains("强风").contains("西区");
        assertThat(futureDraft.path("reviewerQualification").path("qualified").asBoolean()).isTrue();

        /* 4) 操作员未到位，复核员无法就绪（赵尝试）→ 400 */
        mvc.perform(post("/api/watch/{id}/ready", strongWatchId).header("X-Staff-Id", zhao))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("尚未确认现场到位")));

        /* 5) 别人不能替操作员确认到位（孙是主管也不行）→ 403；钱本人到位 → 待复核 */
        mvc.perform(post("/api/watch/{id}/operator-arrive", strongWatchId).header("X-Staff-Id", sun))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/watch/{id}/operator-arrive", strongWatchId).header("X-Staff-Id", qian))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));

        /* 6) 资质不齐，赵就绪仍被拒，逐项缺口返回（409 状态冲突） */
        mvc.perform(post("/api/watch/{id}/ready", strongWatchId).header("X-Staff-Id", zhao))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("西区")));

        /* 7) 跨午夜 + 三端一致性：明天 R-STRONG 起飞 00:30（凌晨），赵在
              人员列表试算、航线入口、值守详情三处都应合格/不合格一致。 */
        String takeoff = tomorrow.atTime(0, 30).format(iso);
        // 人员列表试算
        JsonNode staffTrial = json(mvc.perform(get("/api/ground/staff")
                        .param("routeId", String.valueOf(rStrong)).param("takeoff", takeoff))
                .andExpect(status().isOk()).andReturn());
        assertThat(qualifiedOf(staffTrial, zhao)).isTrue();
        assertThat(qualifiedOf(staffTrial, qian)).isFalse();
        // 航线入口（按该起飞时刻所在日）
        JsonNode entries = json(mvc.perform(get("/api/watch/route-entries").param("takeoff", takeoff))
                .andExpect(status().isOk()).andReturn());
        JsonNode strongEntry = findRouteEntry(entries, rStrong);
        assertThat(strongEntry.path("reviewerQualified").asBoolean()).isTrue();   // 赵
        assertThat(strongEntry.path("operatorQualified").asBoolean()).isFalse();  // 钱
        // 值守详情（读时按同一口径重算，结论一致）
        JsonNode detail = json(mvc.perform(get("/api/watch/{id}", strongWatchId)).andReturn());
        assertThat(detail.path("reviewerQualification").path("qualified").asBoolean()).isTrue();
        assertThat(detail.path("operatorQualification").path("qualified").asBoolean()).isFalse();

        /* 8) 合法就绪路径：明天 R-GALE（疾风/北区），操作员赵(全能证)、复核员李(北区全风级证)。
              起飞 23:30、结束次日 02:00（跨午夜），按起飞时刻两证均有效。 */
        String galeBody = om.writeValueAsString(java.util.Map.of(
                "routeId", rGale, "operatorId", zhao, "reviewerId", li,
                "plannedTakeoff", tomorrow.atTime(23, 30).format(iso),
                "plannedEnd", tomorrow.plusDays(1).atTime(2, 0).format(iso)));
        JsonNode galeWatch = json(mvc.perform(post("/api/watch").header("X-Staff-Id", sun)
                        .contentType(MediaType.APPLICATION_JSON).content(galeBody))
                .andExpect(status().isOk()).andReturn());
        long galeWatchId = galeWatch.path("id").asLong();

        mvc.perform(post("/api/watch/{id}/operator-arrive", galeWatchId).header("X-Staff-Id", zhao))
                .andExpect(status().isOk());
        // 操作员不能复核自己（赵既是操作员，不能点就绪）
        mvc.perform(post("/api/watch/{id}/ready", galeWatchId).header("X-Staff-Id", zhao))
                .andExpect(status().isForbidden());
        // 安全主管也不能代行复核
        mvc.perform(post("/api/watch/{id}/ready", galeWatchId).header("X-Staff-Id", sun))
                .andExpect(status().isForbidden());
        // 复核员李就绪成功，快照冻结
        mvc.perform(post("/api/watch/{id}/ready", galeWatchId).header("X-Staff-Id", li))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"));

        JsonNode readyDetail = json(mvc.perform(get("/api/watch/{id}", galeWatchId)).andReturn());
        assertThat(readyDetail.path("operatorSnapshotCertNo").asText()).isEqualTo("C-ZHAO-ALL");
        assertThat(readyDetail.path("reviewerSnapshotCertNo").asText()).isEqualTo("C-LI-NORTH");
        assertThat(readyDetail.path("snapshotRequiredZones")).
                anySatisfy(n -> assertThat(n.asText()).isEqualTo("北区"));
        assertThat(readyDetail.path("snapshotRouteWindLevel").asText()).isEqualTo("疾风");
        assertThat(readyDetail.path("snapshotEnd").asText()).startsWith(tomorrow.plusDays(1).toString());

        /* 9) 历史快照不随后改变化：把赵改名，就绪值守快照姓名仍是“赵卫东” */
        GroundStaff zhaoEntity = staffRepository.findById(zhao).orElseThrow();
        String renamed = "赵卫东-已改名";
        mvc.perform(put("/api/ground/staff/{id}", zhao).header("X-Staff-Id", sun)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(java.util.Map.of(
                                "staffCode", zhaoEntity.getStaffCode(),
                                "staffName", renamed,
                                "staffRole", zhaoEntity.getStaffRole()))))
                .andExpect(status().isOk());
        JsonNode readyAfterRename = json(mvc.perform(get("/api/watch/{id}", galeWatchId)).andReturn());
        assertThat(readyAfterRename.path("operatorSnapshotName").asText()).isEqualTo("赵卫东");

        /* 10) 普通值班员不能取消已就绪值守（403 且状态不变）；主管可取消 */
        mvc.perform(post("/api/watch/{id}/cancel", galeWatchId).header("X-Staff-Id", qian)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"想取消\"}"))
                .andExpect(status().isForbidden());
        assertThat(watchRepository.findById(galeWatchId).orElseThrow().getStatus())
                .isEqualTo("READY");
        mvc.perform(post("/api/watch/{id}/cancel", galeWatchId).header("X-Staff-Id", sun)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"天气突变\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        /* 11) 安全主管吊销赵的全能证：明天 R-STRONG 未就绪值守立即重新判定打回（带原因），
               已取消的 R-GALE 与昨天已就绪历史都不动、快照保留。 */
        JsonNode revokeResp = json(mvc.perform(post("/api/ground/cert/{id}/revoke", certIdOfZhaoAll())
                        .header("X-Staff-Id", sun)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"违规作业\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(revokeResp.path("requalifiedWatches").asInt()).isGreaterThanOrEqualTo(1);

        FlightWatch strongWatch = watchRepository.findById(strongWatchId).orElseThrow();
        assertThat(strongWatch.getStatus()).isEqualTo("DRAFT");
        assertThat(strongWatch.getRequalifyReason()).contains("C-ZHAO-ALL", "吊销");
        assertThat(strongWatch.getOperatorArrived()).isZero();

        // 昨天已就绪的跨午夜历史：状态、快照姓名/证号完全保持
        FlightRoute gale = routeRepository.findById(rGale).orElseThrow();
        List<FlightWatch> pastReady = watchRepository
                .findByRouteIdOrderByFlightDateDescIdDesc(rGale).stream()
                .filter(w -> w.getStatus().equals("READY"))
                .toList();
        assertThat(pastReady).hasSize(1);
        FlightWatch past = pastReady.get(0);
        assertThat(past.getOperatorSnapshotName()).isEqualTo("赵卫东");
        assertThat(past.getOperatorSnapshotCertNo()).isEqualTo("C-ZHAO-ALL");
        assertThat(past.getReviewerSnapshotCertNo()).isEqualTo("C-LI-NORTH");
        assertThat(past.getSnapshotTakeoff().toLocalDate()).isEqualTo(LocalDate.now().minusDays(1));

        /* 12) 已吊销证书的人员不能通过改飞行日重新获得资格：
               新建一名只有一张长期有效证的人员，排班后吊销，再把飞行日改到更远未来，
               其代表证书状态必须恒为 REVOKED（无论起飞时刻）。 */
        GroundStaff single = GroundStaff.builder()
                .staffCode("S-NEW-" + System.nanoTime()).staffName("新值班").staffRole(GroundStaff.ROLE_STATION_OFFICER)
                .status(1).build();
        staffRepository.save(single);
        com.px.base.entity.GroundCert singleCert = com.px.base.entity.GroundCert.builder()
                .certNo("C-SOLO-" + System.nanoTime()).staffId(single.getId())
                .windLevels("强风,疾风").anchorZones("东区,西区,南区,北区,中区")
                .effectiveDate(LocalDate.now().minusDays(1)).expiryDate(LocalDate.now().plusYears(2))
                .revoked(0).build();
        certRepository.save(singleCert);

        String soloBody = om.writeValueAsString(java.util.Map.of(
                "routeId", rStrong, "operatorId", single.getId(), "reviewerId", li,
                "plannedTakeoff", LocalDate.now().plusDays(2).atTime(9, 0).format(iso)));
        JsonNode soloWatch = json(mvc.perform(post("/api/watch").header("X-Staff-Id", sun)
                        .contentType(MediaType.APPLICATION_JSON).content(soloBody))
                .andExpect(status().isOk()).andReturn());
        long soloWatchId = soloWatch.path("id").asLong();

        // 吊销前：新人员资质满足
        JsonNode before = json(mvc.perform(get("/api/watch/{id}", soloWatchId)).andReturn());
        assertThat(before.path("operatorQualification").path("qualified").asBoolean()).isTrue();

        // 主管吊销
        mvc.perform(post("/api/ground/cert/{id}/revoke", singleCert.getId()).header("X-Staff-Id", sun)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"测试吊销终态\"}"))
                .andExpect(status().isOk());

        // 把飞行日改到 60 天后：代表证书状态仍然是 REVOKED，不能复活
        String movedSoloBody = om.writeValueAsString(java.util.Map.of(
                "routeId", rStrong, "operatorId", single.getId(), "reviewerId", li,
                "plannedTakeoff", LocalDate.now().plusDays(60).atTime(9, 0).format(iso)));
        mvc.perform(put("/api/watch/{id}", soloWatchId).header("X-Staff-Id", sun)
                        .contentType(MediaType.APPLICATION_JSON).content(movedSoloBody))
                .andExpect(status().isOk());
        JsonNode moved = json(mvc.perform(get("/api/watch/{id}", soloWatchId)).andReturn());
        assertThat(moved.path("operatorQualification").path("activeCertStatus").asText())
                .isEqualTo("REVOKED");
        assertThat(moved.path("operatorQualification").path("qualified").asBoolean()).isFalse();

        // 人员列表在遥远未来起飞时刻试算，结论同样是 REVOKED
        JsonNode farTrial = json(mvc.perform(get("/api/ground/staff")
                        .param("routeId", String.valueOf(rStrong))
                        .param("takeoff", LocalDate.now().plusYears(1).atTime(9, 0).format(iso)))
                .andExpect(status().isOk()).andReturn());
        assertThat(qualifiedOf(farTrial, single.getId())).isFalse();
    }

    private long certIdOfZhaoAll() {
        return certRepository.findByCertNo("C-ZHAO-ALL").orElseThrow().getId();
    }

    private JsonNode findWatch(JsonNode watches, LocalDate date, String status) {
        for (JsonNode w : watches) {
            if (date.toString().equals(w.path("flightDate").asText())
                    && status.equals(w.path("status").asText())) {
                return w;
            }
        }
        throw new AssertionError("未找到 " + date + " " + status + " 值守");
    }

    private JsonNode findRouteEntry(JsonNode entries, long routeId) {
        for (JsonNode e : entries) {
            if (e.path("routeId").asLong() == routeId) return e;
        }
        throw new AssertionError("未找到航线入口 " + routeId);
    }

    private boolean qualifiedOf(JsonNode staffArray, long staffId) {
        for (JsonNode s : staffArray) {
            if (s.path("id").asLong() == staffId) {
                return s.path("qualification").path("qualified").asBoolean();
            }
        }
        throw new AssertionError("人员列表未含 " + staffId);
    }
}
