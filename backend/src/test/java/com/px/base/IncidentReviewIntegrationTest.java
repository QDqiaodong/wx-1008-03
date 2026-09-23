package com.px.base;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.px.base.entity.Anchor;
import com.px.base.entity.FlightRoute;
import com.px.base.entity.FlightWatch;
import com.px.base.entity.GroundStaff;
import com.px.base.repository.AnchorRepository;
import com.px.base.repository.FlightRouteRepository;
import com.px.base.repository.FlightWatchRepository;
import com.px.base.repository.GroundStaffRepository;
import com.px.base.repository.IncidentAnchorSnapshotRepository;
import com.px.base.repository.IncidentEventRepository;
import com.px.base.repository.IncidentRevisionRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 飞行异常事件复盘端到端验收（H2 + MockMvc，Redis 深桩）：
 * 覆盖快照冻结与差异、整份版本冲突、不可变修订、封存/重开轮次、服务端权限五大主线。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("itest")
class IncidentReviewIntegrationTest {

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
    @Autowired AnchorRepository anchorRepository;
    @Autowired IncidentEventRepository eventRepository;
    @Autowired IncidentRevisionRepository revisionRepository;
    @Autowired IncidentAnchorSnapshotRepository anchorSnapshotRepository;

    private final String ts = String.valueOf(System.nanoTime());

    private JsonNode json(MvcResult r) throws Exception {
        JsonNode root = om.readTree(r.getResponse().getContentAsString());
        return root.has("data") ? root.path("data") : root;
    }

    /** 准备一组独立的航线/锚点/人员/已结束值守，避免与其他测试的种子数据互相干扰 */
    private Map<String, Object> prepareFixture() {
        FlightRoute route = FlightRoute.builder()
                .routeCode("R-INC-" + ts)
                .routeName("事件测试航线原名")
                .routeGroup("测试组")
                .windSpeed(new BigDecimal("12.00"))
                .windLevel("强风")
                .description("")
                .status(1)
                .build();
        routeRepository.save(route);

        Anchor anchor = Anchor.builder()
                .anchorCode("A-INC-" + ts)
                .maxWeight(new BigDecimal("1800.00"))
                .minWindSpeed(new BigDecimal("8.00"))
                .maxWindSpeed(new BigDecimal("16.00"))
                .locationDesc("事发时位置：东端第一桩")
                .anchorZone("东区")
                .status(1)
                .build();
        anchorRepository.save(anchor);

        GroundStaff officerA = staff(GroundStaff.ROLE_STATION_OFFICER, "事件员甲" + ts);
        GroundStaff officerB = staff(GroundStaff.ROLE_STATION_OFFICER, "事件员乙" + ts);
        GroundStaff officerC = staff(GroundStaff.ROLE_STATION_OFFICER, "事件员丙" + ts);
        GroundStaff safety = staff(GroundStaff.ROLE_SAFETY_OFFICER, "事件主管" + ts);

        LocalDateTime takeoff = LocalDateTime.now().minusHours(5);
        FlightWatch watch = FlightWatch.builder()
                .routeId(route.getId())
                .routeCode(route.getRouteCode())
                .flightDate(takeoff.toLocalDate())
                .plannedTakeoff(takeoff)
                .plannedEnd(takeoff.plusHours(2))
                .operatorId(officerA.getId())
                .operatorName(officerA.getStaffName())
                .reviewerId(officerB.getId())
                .reviewerName(officerB.getStaffName())
                .status(FlightWatch.STATUS_READY)
                .operatorArrived(1)
                .reviewerArrived(1)
                .build();
        watchRepository.save(watch);

        return Map.of("route", route, "anchor", anchor, "a", officerA, "b", officerB,
                "c", officerC, "safety", safety, "watch", watch);
    }

    private GroundStaff staff(String role, String name) {
        return staffRepository.save(GroundStaff.builder()
                .staffCode("S-INC-" + name)
                .staffName(name)
                .staffRole(role)
                .status(1)
                .build());
    }

    private Map<String, Object> createBody(Map<String, Object> fx) {
        FlightRoute route = (FlightRoute) fx.get("route");
        Anchor anchor = (Anchor) fx.get("anchor");
        FlightWatch watch = (FlightWatch) fx.get("watch");
        GroundStaff a = (GroundStaff) fx.get("a");
        GroundStaff b = (GroundStaff) fx.get("b");
        Map<String, Object> body = new HashMap<>();
        body.put("title", "强风下坠飘异常-" + ts);
        body.put("severity", "MAJOR");
        body.put("foundTime", LocalDateTime.now().minusHours(4).withNano(0).toString());
        body.put("routeId", route.getId());
        body.put("watchId", watch.getId());
        body.put("anchorIds", List.of(anchor.getId()));
        body.put("involvedStaffIds", List.of(a.getId(), b.getId()));
        body.put("sceneNarrative", "第五架次降落阶段遭遇阵风，伞翼向右偏移约3米。");
        body.put("handlingActions", "现场停止后续架次，检查锚点与绳索。");
        body.put("evidenceNote", "现场照片3张（相机IMG_" + ts + "），值班记录第2页。");
        return body;
    }

    /**
     * 模拟前端“整份表单”提交：contentState 保存当前已加载版本的完整正文，
     * 每次编辑在其基础上改若干字段（整份口径下前端必然回传全部字段）。
     */
    private final Map<String, Object> contentState = new LinkedHashMap<>();

    private void initContentState() {
        contentState.clear();
        contentState.put("title", "强风下坠飘异常-" + ts);
        contentState.put("severity", "MAJOR");
        contentState.put("foundTime", LocalDateTime.now().minusHours(4).withNano(0).toString());
        contentState.put("sceneNarrative", "第五架次降落阶段遭遇阵风，伞翼向右偏移约3米。");
        contentState.put("handlingActions", "现场停止后续架次，检查锚点与绳索。");
        contentState.put("evidenceNote", "现场照片3张（相机IMG_" + ts + "），值班记录第2页。");
        contentState.put("causeConclusion", null);
        contentState.put("correctiveAction", null);
        contentState.put("ownerId", null);
        contentState.put("dueDate", null);
    }

    private String contentWith(Map<String, Object> mutations, boolean updateState) {
        Map<String, Object> copy = new LinkedHashMap<>(contentState);
        copy.putAll(mutations);
        if (updateState) {
            contentState.putAll(mutations);
        }
        try {
            return om.writeValueAsString(copy);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void 全链路验收() throws Exception {
        Map<String, Object> fx = prepareFixture();
        initContentState();
        long a = ((GroundStaff) fx.get("a")).getId();
        long b = ((GroundStaff) fx.get("b")).getId();
        long c = ((GroundStaff) fx.get("c")).getId();
        long safety = ((GroundStaff) fx.get("safety")).getId();
        long routeId = ((FlightRoute) fx.get("route")).getId();
        long anchorId = ((Anchor) fx.get("anchor")).getId();

        /* ========== 1) 建档：真实航线 + 值守 + 锚点，快照冻结 ========== */
        JsonNode created = json(mvc.perform(post("/api/incident").header("X-Staff-Id", a)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(createBody(fx))))
                .andExpect(status().isOk()).andReturn());
        long incidentId = created.path("id").asLong();
        String code = created.path("incidentCode").asText();
        assertThat(code).startsWith("INC-");
        assertThat(created.path("version").asLong()).isZero();
        assertThat(created.path("status").asText()).isEqualTo("DRAFT");
        // 值守两人自动并入涉及人员
        assertThat(created.path("involvedStaffNames").asText()).contains("事件员甲", "事件员乙");
        assertThat(created.path("snapWatchOperatorName").asText()).startsWith("事件员甲");
        // 建档即有 seq=0 修订
        assertThat(created.path("revisions")).hasSize(1);
        assertThat(created.path("revisions").get(0).path("revisionType").asText()).isEqualTo("CREATE");

        /* ========== 2) 修改基础资料后，事发快照不变，并给出当前差异 ========== */
        FlightRoute route = routeRepository.findById(routeId).orElseThrow();
        route.setRouteName("事件测试航线-已改名");
        route.setWindLevel("疾风");
        route.setWindSpeed(new BigDecimal("17.50"));
        routeRepository.save(route);

        Anchor anchor = anchorRepository.findById(anchorId).orElseThrow();
        anchor.setStatus(0);
        anchor.setLocationDesc("停用后位置改注：西端");
        anchorRepository.save(anchor);

        GroundStaff staffA = staffRepository.findById(a).orElseThrow();
        staffA.setStaffName("事件员甲-已改名");
        staffRepository.save(staffA);
        GroundStaff staffB = staffRepository.findById(b).orElseThrow();
        staffB.setStaffName("事件员乙-已改名");
        staffRepository.save(staffB);

        JsonNode afterChange = json(mvc.perform(get("/api/incident/{id}", incidentId)
                .header("X-Staff-Id", a)).andExpect(status().isOk()).andReturn());
        // 快照原样
        assertThat(afterChange.path("snapRouteName").asText()).isEqualTo("事件测试航线原名");
        assertThat(afterChange.path("snapRouteWindLevel").asText()).isEqualTo("强风");
        JsonNode anchorSnap = afterChange.path("anchorSnapshots").get(0);
        assertThat(anchorSnap.path("anchorStatus").asInt()).isEqualTo(1);
        assertThat(anchorSnap.path("locationDesc").asText()).contains("东端");
        assertThat(afterChange.path("snapWatchOperatorName").asText()).startsWith("事件员甲");
        assertThat(afterChange.path("reporterName").asText()).startsWith("事件员甲");
        // 当前值与差异
        assertThat(afterChange.path("currentRouteName").asText()).contains("已改名");
        assertThat(afterChange.path("routeDiffFields").toString()).contains("航线名称", "风级", "风速");
        assertThat(anchorSnap.path("currentAnchorStatus").asInt()).isZero();
        assertThat(anchorSnap.path("diffFields").toString()).contains("锚点状态", "位置描述");

        /* ========== 3) 无权限人员：列表看不到、详情 403、写操作 403（直接请求后端同样拒绝） ========== */
        JsonNode listForBystander = json(mvc.perform(get("/api/incident").header("X-Staff-Id", c))
                .andExpect(status().isOk()).andReturn());
        for (JsonNode item : listForBystander) {
            assertThat(item.path("id").asLong()).isNotEqualTo(incidentId);
        }
        mvc.perform(get("/api/incident/{id}", incidentId).header("X-Staff-Id", c))
                .andExpect(status().isForbidden());
        // 匿名（未带 X-Staff-Id）读列表 → 401
        mvc.perform(get("/api/incident")).andExpect(status().isUnauthorized());
        // 普通值班员不能封存/重新开启（即使是参与人，直接请求后端）
        mvc.perform(post("/api/incident/{id}/seal", incidentId).header("X-Staff-Id", a)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/incident/{id}/reopen", incidentId).header("X-Staff-Id", a)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"basis\":\"试试\"}"))
                .andExpect(status().isForbidden());

        /* ========== 4) 草稿补充（甲） → 进入调查（乙） → 调查中修订留痕 ========== */
        JsonNode v1 = json(mvc.perform(post("/api/incident/{id}/edit", incidentId).header("X-Staff-Id", a)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"content\":"
                                + contentWith(Map.of("sceneNarrative", "草稿补充：现场风速仪记录峰值14.2m/s。"), true) + "}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(v1.path("version").asLong()).isEqualTo(1);
        assertThat(v1.path("sceneNarrative").asText()).contains("14.2");

        JsonNode v2 = json(mvc.perform(post("/api/incident/{id}/enter-investigating", incidentId)
                        .header("X-Staff-Id", b)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(v2.path("status").asText()).isEqualTo("INVESTIGATING");
        assertThat(v2.path("version").asLong()).isEqualTo(2);

        // 进入调查后的修订必须留痕：操作者/时间/前后内容（v3）
        JsonNode v3 = json(mvc.perform(post("/api/incident/{id}/edit", incidentId).header("X-Staff-Id", b)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2,\"reason\":\"补充监控复核结果\",\"content\":"
                                + contentWith(Map.of(
                                        "sceneNarrative", "调查修订：复核监控录像确认偏移3.2米，阵风持续约8秒。",
                                        "causeConclusion", "结论：瞬时阵风超出当日值守口径，锚点东区绳索略有松弛。"), true)
                                + "}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(v3.path("version").asLong()).isEqualTo(3);
        JsonNode lastRev = v3.path("revisions").get(v3.path("revisions").size() - 1);
        assertThat(lastRev.path("operatorName").asText()).startsWith("事件员乙");
        assertThat(lastRev.path("beforeContent").asText()).contains("14.2");
        assertThat(lastRev.path("afterContent").asText()).contains("3.2米");
        assertThat(lastRev.path("changedFields").toString()).contains("现场经过", "原因结论");

        /* ========== 5) 整份版本冲突：甲乙同基于 v3，甲先改成 v4，乙拿 v3 确认 → 409 ========== */
        // 乙打开的是 v3：保存 v3 完整正文基线（甲随后修改不会影响乙手里的版本）
        Map<String, Object> v3State = new LinkedHashMap<>(contentState);
        JsonNode v4 = json(mvc.perform(post("/api/incident/{id}/edit", incidentId).header("X-Staff-Id", a)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":3,\"reason\":\"甲先改\",\"content\":"
                                + contentWith(Map.of("handlingActions", "甲的修订：增加风速仪校准安排。"), true) + "}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(v4.path("version").asLong()).isEqualTo(4);

        // 乙仍基于 v3 的完整正文，只改证据说明
        Map<String, Object> bCopy = new LinkedHashMap<>(v3State);
        bCopy.put("evidenceNote", "乙的修订：追加监控视频片段编号VIDEO-77。");
        String bEdit = om.writeValueAsString(bCopy);
        MvcResult conflictRes = mvc.perform(post("/api/incident/{id}/edit", incidentId).header("X-Staff-Id", b)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":3,\"reason\":\"乙后改\",\"content\":" + bEdit + "}"))
                .andExpect(status().isConflict()).andReturn();
        JsonNode body = om.readTree(conflictRes.getResponse().getContentAsString());
        JsonNode conflict = body.path("data");
        assertThat(conflict.path("expectedVersion").asLong()).isEqualTo(3);
        assertThat(conflict.path("latestVersion").asLong()).isEqualTo(4);
        assertThat(conflict.path("conflictFields").toString()).contains("处置动作", "事件状态");
        // 冲突时最新事件随响应返回；第一次修改不丢
        assertThat(conflict.path("latest").path("handlingActions").asText()).contains("风速仪校准");
        // 乙的提交整份未落库：证据说明仍是旧值，版本仍 4
        JsonNode stillV4 = json(mvc.perform(get("/api/incident/{id}", incidentId).header("X-Staff-Id", a))
                .andReturn());
        assertThat(stillV4.path("version").asLong()).isEqualTo(4);
        assertThat(stillV4.path("evidenceNote").asText()).doesNotContain("VIDEO-77");
        // 乙基于最新 v4 重新编辑成功（v5）
        JsonNode v5 = json(mvc.perform(post("/api/incident/{id}/edit", incidentId).header("X-Staff-Id", b)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":4,\"reason\":\"乙基于v4重提\",\"content\":"
                                + contentWith(Map.of("evidenceNote", "乙基于最新版本重新编辑：追加VIDEO-77。"), true)
                                + "}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(v5.path("version").asLong()).isEqualTo(5);
        assertThat(v5.path("handlingActions").asText()).contains("风速仪校准");
        assertThat(v5.path("evidenceNote").asText()).contains("VIDEO-77");

        /* ========== 6) 待封存四要素闸门 + 状态变更与修订同事务 ========== */
        MvcResult failedReq = mvc.perform(post("/api/incident/{id}/request-seal", incidentId)
                        .header("X-Staff-Id", a)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":5}"))
                .andExpect(status().isBadRequest()).andReturn();
        String failMsg = om.readTree(failedReq.getResponse().getContentAsString()).path("message").asText();
        assertThat(failMsg).contains("无法提交待封存")
                .contains("纠正措施").contains("负责人").contains("整改期限");
        // 失败的状态流转：状态未动、修订未增加（不会出现半成品）
        int revsAfterFailed = revisionRepository.findByIncidentIdOrderBySeqAsc(incidentId).size();
        assertThat(eventRepository.findById(incidentId).orElseThrow().getStatus()).isEqualTo("INVESTIGATING");
        assertThat(revisionRepository.findByIncidentIdOrderBySeqAsc(incidentId)).hasSize(revsAfterFailed);

        // 补齐其余三要素（原因结论已在调查修订中给出）v6，再提交待封存（v7）
        JsonNode v6 = json(mvc.perform(post("/api/incident/{id}/edit", incidentId).header("X-Staff-Id", b)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":5,\"content\":"
                                + contentWith(Map.of(
                                        "correctiveAction", "措施：风级超标预警阈值下调一级并复训；东区锚点绳索全数更换。",
                                        "ownerId", c,
                                        "dueDate", LocalDate.now().plusDays(15).toString()), true)
                                + "}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(v6.path("ownerName").asText()).startsWith("事件员丙");
        JsonNode v7 = json(mvc.perform(post("/api/incident/{id}/request-seal", incidentId).header("X-Staff-Id", a)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":6}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(v7.path("status").asText()).isEqualTo("PENDING_SEAL");
        assertThat(v7.path("version").asLong()).isEqualTo(7);

        // 参与人乙不能封存 → 403 且状态不动（直接请求后端）
        mvc.perform(post("/api/incident/{id}/seal", incidentId).header("X-Staff-Id", b)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":7}"))
                .andExpect(status().isForbidden());
        // 安全主管拿旧版本封存 → 409（版本口径同样适用于状态流转）
        mvc.perform(post("/api/incident/{id}/seal", incidentId).header("X-Staff-Id", safety)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":6}"))
                .andExpect(status().isConflict());

        /* ========== 7) 安全主管封存（v8，第1轮）：状态+修订+轮次同事务落库 ========== */
        JsonNode v8 = json(mvc.perform(post("/api/incident/{id}/seal", incidentId).header("X-Staff-Id", safety)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":7,\"basis\":\"证据链闭合\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(v8.path("status").asText()).isEqualTo("SEALED");
        assertThat(v8.path("version").asLong()).isEqualTo(8);
        assertThat(v8.path("currentSealRound").asInt()).isEqualTo(1);
        assertThat(v8.path("sealRounds")).hasSize(1);
        assertThat(v8.path("sealRounds").get(0).path("sealedByName").asText()).contains("事件主管");
        // 封存成功后：状态与修订条数一致（不会出现“已封存但修订缺失”）
        var revisions = revisionRepository.findByIncidentIdOrderBySeqAsc(incidentId);
        // seq0..8：CREATE、草稿补充、进入调查、调查修订、甲改、乙重提、补四要素、提交封存、封存
        assertThat(revisions).hasSize(9);
        assertThat(revisions.get(revisions.size() - 1).getRevisionType()).isEqualTo("SEAL");

        /* ========== 8) 封存后覆盖原文被拒绝；只有“重开 + 带理由更正”形成新修订 ========== */
        String overwrite = contentWith(Map.of("sceneNarrative", "试图直接抹掉现场经过。"), false);
        mvc.perform(post("/api/incident/{id}/edit", incidentId).header("X-Staff-Id", a)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":8,\"content\":" + overwrite + "}"))
                .andExpect(status().isConflict());
        // 版本未变、正文未被覆盖
        var sealedEvent = eventRepository.findById(incidentId).orElseThrow();
        assertThat(sealedEvent.getVersion()).isEqualTo(8);
        assertThat(sealedEvent.getSceneNarrative()).contains("3.2米");

        // 重开必须写依据：缺依据 400
        mvc.perform(post("/api/incident/{id}/reopen", incidentId).header("X-Staff-Id", safety)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":8}"))
                .andExpect(status().isBadRequest());
        // 参与人不能重开 → 403
        mvc.perform(post("/api/incident/{id}/reopen", incidentId).header("X-Staff-Id", b)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":8,\"basis\":\"新视频\"}"))
                .andExpect(status().isForbidden());

        JsonNode v9 = json(mvc.perform(post("/api/incident/{id}/reopen", incidentId).header("X-Staff-Id", safety)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":8,\"basis\":\"取得新的气象站数据，原阵风峰值认定需要修正\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(v9.path("status").asText()).isEqualTo("REOPENED");
        assertThat(v9.path("version").asLong()).isEqualTo(9);
        // 第1轮封存行回填了重开信息
        JsonNode round1 = v9.path("sealRounds").get(0);
        assertThat(round1.path("reopened").asBoolean()).isTrue();
        assertThat(round1.path("reopenBasis").asText()).contains("气象站");

        // 重开态更正无理由 → 拒绝；带理由 → CORRECT 新修订（v10），旧版仍在
        String correctNoReason = contentWith(Map.of("sceneNarrative", "更正：结合气象站数据修正为阵风13.8m/s。"), false);
        mvc.perform(post("/api/incident/{id}/edit", incidentId).header("X-Staff-Id", b)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":9,\"content\":" + correctNoReason + "}"))
                .andExpect(status().isBadRequest());
        JsonNode v10 = json(mvc.perform(post("/api/incident/{id}/edit", incidentId).header("X-Staff-Id", b)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":9,\"reason\":\"气象站数据比手持风速仪更具权威性\",\"content\":"
                                + contentWith(Map.of(
                                        "sceneNarrative", "更正：结合气象站数据修正为阵风13.8m/s，偏移3.2米结论不变。"), true)
                                + "}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(v10.path("version").asLong()).isEqualTo(10);
        JsonNode correctRev = v10.path("revisions").get(v10.path("revisions").size() - 1);
        assertThat(correctRev.path("revisionType").asText()).isEqualTo("CORRECT");
        assertThat(correctRev.path("sealRound").asInt()).isEqualTo(1);
        assertThat(correctRev.path("reason").asText()).contains("气象站");
        // 旧版正文仍可在修订记录里并排看到（seq8 封存时的内容 vs seq10 更正内容）
        JsonNode sealRev = v10.path("revisions").get(8);
        assertThat(sealRev.path("afterContent").asText()).contains("阵风持续约8秒");
        assertThat(correctRev.path("afterContent").asText()).contains("13.8m/s");

        /* ========== 9) 再次封存：第2轮，能看出两轮封存之间发生了什么 ========== */
        JsonNode v11 = json(mvc.perform(post("/api/incident/{id}/request-seal", incidentId).header("X-Staff-Id", a)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":10}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(v11.path("status").asText()).isEqualTo("PENDING_SEAL");
        assertThat(v11.path("version").asLong()).isEqualTo(11);
        JsonNode v12 = json(mvc.perform(post("/api/incident/{id}/seal", incidentId).header("X-Staff-Id", safety)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":11,\"basis\":\"更正复核完毕，维持主要结论\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(v12.path("status").asText()).isEqualTo("SEALED");
        assertThat(v12.path("currentSealRound").asInt()).isEqualTo(2);
        assertThat(v12.path("sealRounds")).hasSize(2);
        assertThat(v12.path("sealRounds").get(0).path("reopened").asBoolean()).isTrue();
        assertThat(v12.path("sealRounds").get(1).path("reopened").asBoolean()).isFalse();
        // 封存后快照仍不随后续资料修改变化（锚点早在第1轮封存前已被停用）
        JsonNode finalView = json(mvc.perform(get("/api/incident/{id}", incidentId).header("X-Staff-Id", safety))
                .andExpect(status().isOk()).andReturn());
        assertThat(finalView.path("anchorSnapshots").get(0).path("anchorStatus").asInt()).isEqualTo(1);
        assertThat(finalView.path("anchorSnapshots").get(0).path("currentAnchorStatus").asInt()).isZero();

        /* ========== 10) 安全主管可见全部 ========== */
        JsonNode safetyList = json(mvc.perform(get("/api/incident").header("X-Staff-Id", safety))
                .andExpect(status().isOk()).andReturn());
        boolean safetySees = false;
        for (JsonNode item : safetyList) {
            if (item.path("id").asLong() == incidentId) safetySees = true;
        }
        assertThat(safetySees).isTrue();

        /* ========== 11) 修订记录不可变：只追加、seq 连续，锚点快照行数不变 ========== */
        var allRevs = revisionRepository.findByIncidentIdOrderBySeqAsc(incidentId);
        // seq0..12：在 9 条基础上又有 重开、更正、提交封存、封存
        assertThat(allRevs).hasSize(13);
        for (int i = 0; i < allRevs.size(); i++) {
            assertThat(allRevs.get(i).getSeq()).isEqualTo(i);
        }
        assertThat(anchorSnapshotRepository.findByIncidentIdOrderBySortNoAscIdAsc(incidentId)).hasSize(1);
    }
}
