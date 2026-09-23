package com.px.base;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.px.base.entity.Anchor;
import com.px.base.entity.FlightRoute;
import com.px.base.entity.FlightWatch;
import com.px.base.entity.GroundStaff;
import com.px.base.incident.entity.Incident;
import com.px.base.incident.entity.IncidentRevision;
import com.px.base.incident.repository.IncidentRepository;
import com.px.base.incident.repository.IncidentRevisionRepository;
import com.px.base.repository.AnchorRepository;
import com.px.base.repository.FlightRouteRepository;
import com.px.base.repository.FlightWatchRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * “飞行异常事件复盘”端到端验收（H2 + MockMvc；Redis 深桩替身）：
 *  1. 建立关联真实航线/值守/锚点的事件，事发快照冻结；
 *  2. 修改航线/锚点/人员档案，事件快照不变、当前资料能看出差异；
 *  3. 两个身份从同一版本分别修改：第二个得到 409 冲突字段与最新版本，不丢第一次修改；
 *  4. 封存闸门（四项结论 + 仅安全主管）；
 *  5. 封存后覆盖原文被拒；带理由更正产生新版本，可与旧版并排；
 *  6. 重新开启必须写依据，再次封存后修订记录能看到两次封存之间的动作；
 *  7. 行级权限：无关值班员页面接口与直接请求都读不到、封存/重开均 403；
 *  8. 刷新/重新登录（重新带同一 X-Staff-Id 请求）后状态、版本、快照、修订顺序一致；
 *  9. 状态变更与修订流水同事务（计数一致），不存在“已封存但无修订”。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("itest")
class IncidentIntegrationTest {

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
    @Autowired IncidentRepository incidentRepository;
    @Autowired IncidentRevisionRepository revisionRepository;

    private final DateTimeFormatter iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private JsonNode data(MvcResult r) throws Exception {
        JsonNode root = om.readTree(r.getResponse().getContentAsString());
        return root.path("data");
    }

    private long staff(String code) {
        return staffRepository.findByStaffCode(code).orElseThrow().getId();
    }

    private Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void 异常事件全流程验收() throws Exception {
        long zhao = staff("S001");
        long qian = staff("S002");   // 无关值班员
        long sun = staff("S003");    // 安全主管
        long li = staff("S004");     // 种子昨日 R-GALE 值守的复核员

        /* ============ 准备：独立的航线 + 锚点 + 一条已发生的值守 ============ */
        String unique = String.valueOf(System.nanoTime());
        FlightRoute route = routeRepository.save(FlightRoute.builder()
                .routeCode("R-INC-" + unique)
                .routeName("事件验收线-原名")
                .routeGroup("北区")
                .windSpeed(new java.math.BigDecimal("16.00"))
                .windLevel("疾风")
                .description("")
                .status(1).build());

        Anchor anchor = anchorRepository.save(Anchor.builder()
                .anchorCode("A-INC-" + unique)
                .maxWeight(new java.math.BigDecimal("1000.00"))
                .minWindSpeed(new java.math.BigDecimal("0.00"))
                .maxWindSpeed(new java.math.BigDecimal("20.00"))
                .locationDesc("事件验收锚点-原位")
                .anchorZone("北区")
                .status(1).build());

        LocalDate past = LocalDate.now().minusDays(2);
        FlightWatch watch = watchRepository.save(FlightWatch.builder()
                .routeId(route.getId()).routeCode(route.getRouteCode())
                .flightDate(past).plannedTakeoff(past.atTime(10, 0)).plannedEnd(past.atTime(11, 0))
                .operatorId(zhao).operatorName(staffRepository.findById(zhao).orElseThrow().getStaffName())
                .reviewerId(li).reviewerName(staffRepository.findById(li).orElseThrow().getStaffName())
                .status(FlightWatch.STATUS_READY).operatorArrived(1).reviewerArrived(1)
                .readyTime(past.atTime(9, 30)).build());

        /* ============ 1) 建立事件（赵报告，关联真实航线/值守/锚点），快照冻结 ============ */
        String createBody = om.writeValueAsString(body(
                "title", "飞行后发现锚点松动",
                "watchId", watch.getId(),
                "routeId", route.getId(),
                "anchorIds", List.of(anchor.getId()),
                "foundTime", LocalDateTime.now().minusHours(2).format(iso),
                "severity", "MAJOR",
                "incidentNote", "降落后巡检发现锚点地脚螺栓松动两圈。",
                "handlingAction", "立即停用该锚点并重新紧固。",
                "evidenceDesc", "照片 EV-001，巡检记录。"));

        JsonNode created = data(mvc.perform(post("/api/incidents").header("X-Staff-Id", zhao)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk()).andReturn());
        long incId = created.path("id").asLong();
        assertThat(created.path("version").asInt()).isEqualTo(1);
        assertThat(created.path("status").asText()).isEqualTo("DRAFT");
        assertThat(created.path("snapshotRouteName").asText()).isEqualTo("事件验收线-原名");
        assertThat(created.path("snapshotRouteWindLevel").asText()).isEqualTo("疾风");
        assertThat(created.path("snapshotOperatorName").asText()).isNotEmpty();
        assertThat(created.path("anchors")).hasSize(1);
        assertThat(created.path("anchors").get(0).path("statusSnapshot").asText()).isEqualTo("启用");
        assertThat(created.path("anchors").get(0).path("changed").asBoolean()).isFalse();
        // 建事件即第 1 版 CREATE
        List<IncidentRevision> revs0 = revisionRepository.findByIncidentIdOrderByRevisionNoAsc(incId);
        assertThat(revs0).hasSize(1);
        assertThat(revs0.get(0).getChangeType()).isEqualTo(IncidentRevision.TYPE_CREATE);

        /* ============ 2) 修改基础资料：航线改名/风级、锚点停用移位、人员改名；快照不变 ============ */
        route.setRouteName("事件验收线-已改名");
        route.setWindSpeed(new java.math.BigDecimal("5.00"));
        route.setWindLevel("轻风");
        routeRepository.save(route);
        anchor.setStatus(0);
        anchor.setLocationDesc("事件验收锚点-已移位");
        anchorRepository.save(anchor);
        GroundStaff zhaoEntity = staffRepository.findById(zhao).orElseThrow();
        String zhaoOldName = zhaoEntity.getStaffName();
        zhaoEntity.setStaffName(zhaoOldName + "-改名");
        staffRepository.save(zhaoEntity);

        JsonNode afterChange = data(mvc.perform(get("/api/incidents/{id}", incId).header("X-Staff-Id", zhao))
                .andExpect(status().isOk()).andReturn());
        // 事发快照保持原名/疾风/启用
        assertThat(afterChange.path("snapshotRouteName").asText()).isEqualTo("事件验收线-原名");
        assertThat(afterChange.path("snapshotRouteWindLevel").asText()).isEqualTo("疾风");
        assertThat(afterChange.path("anchors").get(0).path("statusSnapshot").asText()).isEqualTo("启用");
        assertThat(afterChange.path("anchors").get(0).path("locationDesc").asText()).isEqualTo("事件验收锚点-原位");
        // 当前资料实时反映变化，可对比差异
        assertThat(afterChange.path("currentRouteName").asText()).isEqualTo("事件验收线-已改名");
        assertThat(afterChange.path("currentRouteWindLevel").asText()).isEqualTo("轻风");
        assertThat(afterChange.path("routeChanged").asBoolean()).isTrue();
        assertThat(afterChange.path("anchors").get(0).path("currentStatus").asText()).isEqualTo("停用");
        assertThat(afterChange.path("anchors").get(0).path("changed").asBoolean()).isTrue();
        assertThat(afterChange.path("currentOperatorName").asText()).isEqualTo(zhaoOldName + "-改名");
        assertThat(afterChange.path("staffChanged").asBoolean()).isTrue();

        // 把名字改回，避免影响后续口径
        zhaoEntity.setStaffName(zhaoOldName);
        staffRepository.save(zhaoEntity);

        /* ============ 3) 草稿 → 调查中（赵报告人） ============ */
        data(mvc.perform(post("/api/incidents/{id}/start-investigation", incId).header("X-Staff-Id", zhao)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body("expectedVersion", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INVESTIGATING"))
                .andExpect(jsonPath("$.data.version").value(2)));

        /* ============ 4) 并发：赵、李从同一版本 v2 分别修改，第二个必须看到冲突且不丢第一次修改 ============ */
        String zhaoEdit = om.writeValueAsString(body(
                "expectedVersion", 2,
                "title", "赵改的标题",
                "foundTime", LocalDateTime.now().minusHours(3).format(iso),
                "severity", "CRITICAL",
                "incidentNote", "赵补充：螺栓松动两圈且有锈蚀。",
                "handlingAction", "停用、紧固、更换垫片。",
                "evidenceDesc", "照片 EV-001，巡检记录。"));
        JsonNode zhaoSaved = data(mvc.perform(put("/api/incidents/{id}", incId).header("X-Staff-Id", zhao)
                        .contentType(MediaType.APPLICATION_JSON).content(zhaoEdit))
                .andExpect(status().isOk()).andReturn());
        assertThat(zhaoSaved.path("version").asInt()).isEqualTo(3);
        assertThat(zhaoSaved.path("title").asText()).isEqualTo("赵改的标题");

        String liEdit = om.writeValueAsString(body(
                "expectedVersion", 2, // 仍拿着旧版本
                "title", "李改的标题",
                "foundTime", LocalDateTime.now().minusHours(4).format(iso),
                "severity", "MAJOR",
                "incidentNote", "李补充：现场风速记录正常。",
                "handlingAction", "停用、紧固、更换垫片。",
                "evidenceDesc", "照片 EV-001，巡检记录。"));
        MvcResult conflictRes = mvc.perform(put("/api/incidents/{id}", incId).header("X-Staff-Id", li)
                        .contentType(MediaType.APPLICATION_JSON).content(liEdit))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("版本冲突")))
                .andReturn();
        JsonNode conflict = data(conflictRes);
        assertThat(conflict.path("latestVersion").asInt()).isEqualTo(3);
        assertThat(conflict.path("latest").path("title").asText()).isEqualTo("赵改的标题");
        List<String> conflictFields = new java.util.ArrayList<>();
        conflict.path("conflictFields").forEach(f -> conflictFields.add(f.path("fieldLabel").asText()));
        assertThat(conflictFields).contains("标题", "严重级别", "现场经过");

        // 赵的修改没有被李的失败提交覆盖
        JsonNode stillZhao = data(mvc.perform(get("/api/incidents/{id}", incId).header("X-Staff-Id", sun))
                .andReturn());
        assertThat(stillZhao.path("title").asText()).isEqualTo("赵改的标题");
        assertThat(stillZhao.path("severity").asText()).isEqualTo("CRITICAL");

        // 李基于最新 v3 重新编辑后保存成功（整份口径：他自己决定并入赵的标题）
        String liRebased = om.writeValueAsString(body(
                "expectedVersion", 3,
                "title", "赵改的标题",
                "foundTime", LocalDateTime.now().minusHours(4).format(iso),
                "severity", "CRITICAL",
                "incidentNote", "赵补充：螺栓松动两圈且有锈蚀。李复核：现场风速记录正常。",
                "handlingAction", "停用、紧固、更换垫片。",
                "evidenceDesc", "照片 EV-001，巡检记录。",
                "rootCause", "日常巡检扭矩复核缺失。",
                "correctiveAction", "建立每周扭矩抽检制度。",
                "ownerId", li,
                "dueDate", LocalDate.now().plusDays(14).toString()));
        JsonNode liSaved = data(mvc.perform(put("/api/incidents/{id}", incId).header("X-Staff-Id", li)
                        .contentType(MediaType.APPLICATION_JSON).content(liRebased))
                .andExpect(status().isOk()).andReturn());
        assertThat(liSaved.path("version").asInt()).isEqualTo(4);
        assertThat(liSaved.path("incidentNote").asText()).contains("李复核");

        /* ============ 5) 封存闸门：四项不全不能提交封存；非主管不能封存 ============ */
        // v4 时四项齐备：直接清空 rootCause 后提交封存必须被拒（409 业务闸门），状态仍是调查中
        mvc.perform(post("/api/incidents/{id}/submit-seal", incId).header("X-Staff-Id", li)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body("expectedVersion", 4))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("四项")));
        // 调查中允许把结论改缺（编辑成功 v5），再提交封存仍被闸门拦住
        String clearCause = om.writeValueAsString(body(
                "expectedVersion", 4,
                "title", "赵改的标题",
                "foundTime", LocalDateTime.now().minusHours(4).format(iso),
                "severity", "CRITICAL",
                "incidentNote", liSaved.path("incidentNote").asText(),
                "handlingAction", "停用、紧固、更换垫片。",
                "evidenceDesc", "照片 EV-001，巡检记录。",
                "rootCause", "",
                "correctiveAction", "建立每周扭矩抽检制度。",
                "ownerId", li,
                "dueDate", LocalDate.now().plusDays(14).toString()));
        JsonNode cleared = data(mvc.perform(put("/api/incidents/{id}", incId).header("X-Staff-Id", li)
                        .contentType(MediaType.APPLICATION_JSON).content(clearCause))
                .andExpect(status().isOk()).andReturn());
        assertThat(cleared.path("version").asInt()).isEqualTo(5);
        mvc.perform(post("/api/incidents/{id}/submit-seal", incId).header("X-Staff-Id", li)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body("expectedVersion", 5))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("四项")));
        assertThat(incidentRepository.findById(incId).orElseThrow().getStatus())
                .isEqualTo(Incident.STATUS_INVESTIGATING);

        // 主管也不能跳过“待封存”直接封存调查中的事件
        mvc.perform(post("/api/incidents/{id}/seal", incId).header("X-Staff-Id", sun)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body("expectedVersion", 5))))
                .andExpect(status().isConflict());

        // 补齐结论（v6）→ 提交封存成功（v7 待封存）
        String fillCause = om.writeValueAsString(body(
                "expectedVersion", 5,
                "title", "赵改的标题",
                "foundTime", LocalDateTime.now().minusHours(4).format(iso),
                "severity", "CRITICAL",
                "incidentNote", liSaved.path("incidentNote").asText(),
                "handlingAction", "停用、紧固、更换垫片。",
                "evidenceDesc", "照片 EV-001，巡检记录。",
                "rootCause", "日常巡检扭矩复核缺失。",
                "correctiveAction", "建立每周扭矩抽检制度。",
                "ownerId", li,
                "dueDate", LocalDate.now().plusDays(14).toString()));
        JsonNode refilled = data(mvc.perform(put("/api/incidents/{id}", incId).header("X-Staff-Id", li)
                        .contentType(MediaType.APPLICATION_JSON).content(fillCause))
                .andExpect(status().isOk()).andReturn());
        assertThat(refilled.path("version").asInt()).isEqualTo(6);
        data(mvc.perform(post("/api/incidents/{id}/submit-seal", incId).header("X-Staff-Id", li)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body("expectedVersion", 6))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_SEAL"))
                .andExpect(jsonPath("$.data.version").value(7)));

        // 普通值班员封存 → 403，状态不变
        mvc.perform(post("/api/incidents/{id}/seal", incId).header("X-Staff-Id", zhao)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body("expectedVersion", 7))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("仅安全主管")));
        assertThat(incidentRepository.findById(incId).orElseThrow().getStatus())
                .isEqualTo(Incident.STATUS_PENDING_SEAL);

        // 主管拿旧版本封存 → 409（版本冲突），不能静默推进
        mvc.perform(post("/api/incidents/{id}/seal", incId).header("X-Staff-Id", sun)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body("expectedVersion", 1))))
                .andExpect(status().isConflict());

        // 主管用正确版本封存成功；状态变更与 SEAL 修订同事务（计数同步到 8）
        data(mvc.perform(post("/api/incidents/{id}/seal", incId).header("X-Staff-Id", sun)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body("expectedVersion", 7))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SEALED"))
                .andExpect(jsonPath("$.data.sealedCount").value(1))
                .andExpect(jsonPath("$.data.version").value(8)));
        Incident sealedInc = incidentRepository.findById(incId).orElseThrow();
        assertThat(sealedInc.getStatus()).isEqualTo(Incident.STATUS_SEALED);
        assertThat(revisionRepository.countByIncidentId(incId)).isEqualTo(8);
        assertThat(revisionRepository.findByIncidentIdOrderByRevisionNoAsc(incId).get(7).getChangeType())
                .isEqualTo(IncidentRevision.TYPE_SEAL);

        /* ============ 6) 封存后：直接覆盖原文被拒；带理由更正产生新版本且旧版保留 ============ */
        String overwrite = om.writeValueAsString(body(
                "expectedVersion", 8,
                "title", "被篡改的标题",
                "foundTime", sealedInc.getFoundTime().format(iso),
                "severity", "CRITICAL",
                "incidentNote", "想直接抹掉原经过",
                "handlingAction", "x",
                "evidenceDesc", "照片 EV-001，巡检记录。"));
        // 不带更正理由 → 拒绝
        mvc.perform(put("/api/incidents/{id}", incId).header("X-Staff-Id", sun)
                        .contentType(MediaType.APPLICATION_JSON).content(overwrite))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("更正理由")));
        // 改证据说明 → 拒绝（原证据不可覆盖）
        String changeEvidence = om.writeValueAsString(body(
                "expectedVersion", 8,
                "title", sealedInc.getTitle(),
                "foundTime", sealedInc.getFoundTime().format(iso),
                "severity", "CRITICAL",
                "incidentNote", sealedInc.getIncidentNote(),
                "handlingAction", sealedInc.getHandlingAction(),
                "evidenceDesc", "替换掉原证据",
                "rootCause", sealedInc.getRootCause(),
                "correctiveAction", sealedInc.getCorrectiveAction(),
                "ownerId", sealedInc.getOwnerId(),
                "dueDate", sealedInc.getDueDate().toString(),
                "changeReason", "想换证据"));
        mvc.perform(put("/api/incidents/{id}", incId).header("X-Staff-Id", sun)
                        .contentType(MediaType.APPLICATION_JSON).content(changeEvidence))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("证据")));

        // 合法更正：正文其余字段保持，只追加纠正措施补充，带理由 → v9 CORRECTION，v8 旧版仍在
        String correction = om.writeValueAsString(body(
                "expectedVersion", 8,
                "title", sealedInc.getTitle(),
                "foundTime", sealedInc.getFoundTime().format(iso),
                "severity", "CRITICAL",
                "incidentNote", sealedInc.getIncidentNote(),
                "handlingAction", sealedInc.getHandlingAction(),
                "evidenceDesc", sealedInc.getEvidenceDesc(),
                "rootCause", sealedInc.getRootCause(),
                "correctiveAction", sealedInc.getCorrectiveAction() + "；增加扭矩复核双人签字。",
                "ownerId", sealedInc.getOwnerId(),
                "dueDate", sealedInc.getDueDate().toString(),
                "changeReason", "复查发现还需双人签字环节"));
        JsonNode corrected = data(mvc.perform(put("/api/incidents/{id}", incId).header("X-Staff-Id", sun)
                        .contentType(MediaType.APPLICATION_JSON).content(correction))
                .andExpect(status().isOk()).andReturn());
        assertThat(corrected.path("version").asInt()).isEqualTo(9);
        List<IncidentRevision> revsAfterCorrect = revisionRepository.findByIncidentIdOrderByRevisionNoAsc(incId);
        assertThat(revsAfterCorrect).hasSize(9);
        assertThat(revsAfterCorrect.get(8).getChangeType()).isEqualTo(IncidentRevision.TYPE_CORRECTION);
        assertThat(revsAfterCorrect.get(8).getChangeReason()).contains("双人签字");
        // 旧版 v8 仍可并排查看，内容是更正前的纠正措施；v8 本身是第一次封存版
        IncidentRevision v8 = revsAfterCorrect.get(7);
        IncidentRevision v9 = revsAfterCorrect.get(8);
        assertThat(v8.getChangeType()).isEqualTo(IncidentRevision.TYPE_SEAL);
        assertThat(v8.getCorrectiveAction()).doesNotContain("双人签字");
        assertThat(v9.getCorrectiveAction()).contains("双人签字");
        // 修订记录接口按序号返回，变更字段标出“纠正措施”
        JsonNode revList = data(mvc.perform(get("/api/incidents/{id}/revisions", incId).header("X-Staff-Id", sun))
                .andExpect(status().isOk()).andReturn());
        assertThat(revList).hasSize(9);
        assertThat(revList.get(8).path("changedFields").toString()).contains("纠正措施");

        /* ============ 7) 重新开启必须写依据（非主管 403），之后再次封存可见期间动作 ============ */
        // 无理由 → 拒绝
        mvc.perform(post("/api/incidents/{id}/reopen", incId).header("X-Staff-Id", sun)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body("expectedVersion", 9))))
                .andExpect(status().isBadRequest());
        // 值班员 → 403
        mvc.perform(post("/api/incidents/{id}/reopen", incId).header("X-Staff-Id", zhao)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body("expectedVersion", 9, "reason", "我要重开"))))
                .andExpect(status().isForbidden());
        // 主管写依据重开 → v10 REOPENED
        data(mvc.perform(post("/api/incidents/{id}/reopen", incId).header("X-Staff-Id", sun)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body("expectedVersion", 9, "reason", "出现新证人证言，需补充调查"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REOPENED"))
                .andExpect(jsonPath("$.data.version").value(10)));

        // 重新开启期间再补一条调查修订（两次封存之间发生的事）→ v11
        String reopenedEdit = om.writeValueAsString(body(
                "expectedVersion", 10,
                "title", v9.getTitle(),
                "foundTime", v9.getFoundTime().format(iso),
                "severity", "CRITICAL",
                "incidentNote", v9.getIncidentNote() + "（重开补充：新证人称当时听到异响。）",
                "handlingAction", v9.getHandlingAction(),
                "evidenceDesc", v9.getEvidenceDesc(),
                "rootCause", v9.getRootCause(),
                "correctiveAction", v9.getCorrectiveAction(),
                "ownerId", v9.getOwnerId(),
                "dueDate", v9.getDueDate().toString()));
        data(mvc.perform(put("/api/incidents/{id}", incId).header("X-Staff-Id", sun)
                        .contentType(MediaType.APPLICATION_JSON).content(reopenedEdit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(11)));

        // 重新开启态不能直接封存，需先提交封存 → v12，再封存 → v13（第二次封存）
        mvc.perform(post("/api/incidents/{id}/seal", incId).header("X-Staff-Id", sun)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body("expectedVersion", 11))))
                .andExpect(status().isConflict());
        data(mvc.perform(post("/api/incidents/{id}/submit-seal", incId).header("X-Staff-Id", sun)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body("expectedVersion", 11))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_SEAL"))
                .andExpect(jsonPath("$.data.version").value(12)));
        data(mvc.perform(post("/api/incidents/{id}/seal", incId).header("X-Staff-Id", sun)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body("expectedVersion", 12))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SEALED"))
                .andExpect(jsonPath("$.data.sealedCount").value(2))
                .andExpect(jsonPath("$.data.version").value(13)));

        // 两次封存之间：第一次 SEAL(v8) 之后 REOPEN(v10)、EDIT(v11)、提交封存(v12)、第二次 SEAL(v13) 完整可查
        List<IncidentRevision> finalRevs = revisionRepository.findByIncidentIdOrderByRevisionNoAsc(incId);
        assertThat(finalRevs).extracting(IncidentRevision::getChangeType)
                .containsSubsequence(
                        IncidentRevision.TYPE_SEAL,      // v8 第一次封存
                        IncidentRevision.TYPE_REOPEN,    // v10
                        IncidentRevision.TYPE_EDIT,      // v11 重开后补充
                        IncidentRevision.TYPE_EDIT,      // v12 提交封存
                        IncidentRevision.TYPE_SEAL);     // v13 第二次封存
        assertThat(finalRevs).hasSize(13);

        /* ============ 8) 行级权限：无关值班员读/封存/重开全部被拒（直接请求后端） ============ */
        mvc.perform(get("/api/incidents/{id}", incId).header("X-Staff-Id", qian))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/incidents/{id}/revisions", incId).header("X-Staff-Id", qian))
                .andExpect(status().isForbidden());
        // 列表中看不到该事件
        JsonNode qianList = data(mvc.perform(get("/api/incidents").header("X-Staff-Id", qian))
                .andExpect(status().isOk()).andReturn());
        for (JsonNode item : qianList) {
            assertThat(item.path("id").asLong()).isNotEqualTo(incId);
        }
        // 主管列表能看到全部
        JsonNode sunList = data(mvc.perform(get("/api/incidents").header("X-Staff-Id", sun))
                .andExpect(status().isOk()).andReturn());
        boolean seen = false;
        for (JsonNode item : sunList) if (item.path("id").asLong() == incId) seen = true;
        assertThat(seen).isTrue();
        // 未携带身份 → 401
        mvc.perform(get("/api/incidents/{id}", incId)).andExpect(status().isUnauthorized());

        /* ============ 9) 刷新/重新登录一致性：重新请求得到相同状态/版本/快照/修订顺序 ============ */
        JsonNode reloadA = data(mvc.perform(get("/api/incidents/{id}", incId).header("X-Staff-Id", zhao))
                .andExpect(status().isOk()).andReturn());
        JsonNode reloadB = data(mvc.perform(get("/api/incidents/{id}", incId).header("X-Staff-Id", zhao))
                .andExpect(status().isOk()).andReturn());
        assertThat(reloadA.path("status").asText()).isEqualTo("SEALED");
        assertThat(reloadA.path("version").asInt()).isEqualTo(13);
        assertThat(reloadB.path("status").asText()).isEqualTo("SEALED");
        assertThat(reloadB.path("version").asInt()).isEqualTo(13);
        assertThat(reloadA.path("snapshotRouteName").asText()).isEqualTo("事件验收线-原名");
        assertThat(reloadB.path("snapshotRouteName").asText()).isEqualTo("事件验收线-原名");
        JsonNode revA = data(mvc.perform(get("/api/incidents/{id}/revisions", incId).header("X-Staff-Id", sun)).andReturn());
        JsonNode revB = data(mvc.perform(get("/api/incidents/{id}/revisions", incId).header("X-Staff-Id", sun)).andReturn());
        assertThat(revA.size()).isEqualTo(revB.size()).isEqualTo(13);
        for (int i = 0; i < revA.size(); i++) {
            assertThat(revA.get(i).path("revisionNo").asInt()).isEqualTo(i + 1);
            assertThat(revA.get(i).path("changeType").asText())
                    .isEqualTo(revB.get(i).path("changeType").asText());
        }
    }
}
