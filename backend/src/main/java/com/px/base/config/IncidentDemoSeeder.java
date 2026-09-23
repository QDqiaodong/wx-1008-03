package com.px.base.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.px.base.dto.IncidentContentDTO;
import com.px.base.entity.Anchor;
import com.px.base.entity.FlightRoute;
import com.px.base.entity.FlightWatch;
import com.px.base.entity.GroundStaff;
import com.px.base.entity.IncidentAnchorSnapshot;
import com.px.base.entity.IncidentEvent;
import com.px.base.entity.IncidentRevision;
import com.px.base.entity.IncidentSealRound;
import com.px.base.repository.AnchorRepository;
import com.px.base.repository.FlightRouteRepository;
import com.px.base.repository.FlightWatchRepository;
import com.px.base.repository.GroundStaffRepository;
import com.px.base.repository.IncidentAnchorSnapshotRepository;
import com.px.base.repository.IncidentEventRepository;
import com.px.base.repository.IncidentRevisionRepository;
import com.px.base.repository.IncidentSealRoundRepository;
import com.px.base.rule.Csv;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 异常事件复盘演示种子（幂等，按事件编号判重）：
 * 一条关联昨天已结束 R-GALE 值守的【已封存（第1轮）】事件，
 * 报告人赵卫东、涉及李向北；用于演示快照冻结、修订记录与封存视图。
 * 修订 JSON 与真实正文同构，前端并排对比不会出现假差异。
 */
@Component
@Order(30)
@RequiredArgsConstructor
@Slf4j
public class IncidentDemoSeeder implements CommandLineRunner {

    private static final String DEMO_CODE = "INC-DEMO-0001";

    private final IncidentEventRepository eventRepository;
    private final IncidentAnchorSnapshotRepository anchorSnapshotRepository;
    private final IncidentRevisionRepository revisionRepository;
    private final IncidentSealRoundRepository sealRoundRepository;
    private final FlightRouteRepository routeRepository;
    private final FlightWatchRepository watchRepository;
    private final AnchorRepository anchorRepository;
    private final GroundStaffRepository staffRepository;

    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        if (eventRepository.existsByIncidentCode(DEMO_CODE)) {
            return;
        }
        FlightRoute gale = routeRepository.findByRouteCode("R-GALE").orElse(null);
        GroundStaff zhao = staffRepository.findByStaffCode("S001").orElse(null);
        GroundStaff li = staffRepository.findByStaffCode("S004").orElse(null);
        GroundStaff sun = staffRepository.findByStaffCode("S003").orElse(null);
        if (gale == null || zhao == null || li == null || sun == null) {
            return;
        }
        LocalDate yesterday = LocalDate.now().minusDays(1);
        FlightWatch watch = watchRepository.findByRouteIdOrderByFlightDateDescIdDesc(gale.getId()).stream()
                .filter(w -> w.getFlightDate().equals(yesterday))
                .findFirst().orElse(null);

        LocalDateTime found = yesterday.atTime(23, 50);
        LocalDate due = LocalDate.now().plusDays(20);

        IncidentEvent event = IncidentEvent.builder()
                .incidentCode(DEMO_CODE)
                .title("R-GALE 夜间架次侧风偏移异常（演示事件）")
                .status(IncidentEvent.STATUS_SEALED)
                .version(3L)
                .severity(IncidentEvent.SEVERITY_MAJOR)
                .foundTime(found)
                .sceneNarrative("23:50 最后一架次降落阶段遭遇侧风，伞翼向右偏移约2米，未造成人员伤害。")
                .handlingActions("立即中止后续架次，复检北区锚点 A-GALE2600 与全部绳索。")
                .evidenceNote("记录仪片段 REC-2350；现场照片2张；当班赵卫东、李向北签字记录。")
                .causeConclusion("侧风瞬时超过该批学员训练口径；当值提醒偏晚。")
                .correctiveAction("侧风预警阈值下调1m/s并加装声光提醒；复训侧风处置科目。")
                .ownerId(li.getId())
                .ownerName(li.getStaffName())
                .dueDate(due)
                .routeId(gale.getId())
                .snapRouteCode(gale.getRouteCode())
                .snapRouteName(gale.getRouteName())
                .snapRouteWindLevel(gale.getWindLevel())
                .snapRouteWindSpeed(gale.getWindSpeed())
                .watchId(watch == null ? null : watch.getId())
                .snapWatchTakeoff(watch == null ? null : watch.getPlannedTakeoff())
                .snapWatchEnd(watch == null ? null : watch.getPlannedEnd())
                .snapWatchOperatorId(watch == null ? zhao.getId() : watch.getOperatorId())
                .snapWatchOperatorName(watch == null ? zhao.getStaffName() : watch.getOperatorName())
                .snapWatchReviewerId(watch == null ? li.getId() : watch.getReviewerId())
                .snapWatchReviewerName(watch == null ? li.getStaffName() : watch.getReviewerName())
                .reporterId(zhao.getId())
                .reporterName(zhao.getStaffName())
                .involvedStaffIds(Csv.join(List.of(
                        String.valueOf(watch == null ? zhao.getId() : watch.getOperatorId()),
                        String.valueOf(watch == null ? li.getId() : watch.getReviewerId()))))
                .involvedStaffNames("赵卫东、李向北")
                .currentSealRound(1)
                .build();
        eventRepository.save(event);

        anchorRepository.findByAnchorCode("A-GALE2600").ifPresent(a ->
                anchorSnapshotRepository.save(IncidentAnchorSnapshot.builder()
                        .incidentId(event.getId())
                        .anchorId(a.getId())
                        .anchorCode(a.getAnchorCode())
                        .locationDesc(a.getLocationDesc())
                        .anchorZone(a.getAnchorZone())
                        .anchorStatus(a.getStatus())
                        .maxWeight(a.getMaxWeight())
                        .minWindSpeed(a.getMinWindSpeed())
                        .maxWindSpeed(a.getMaxWindSpeed())
                        .sortNo(0)
                        .build()));

        String fullContent = json.writeValueAsString(IncidentContentDTO.of(event));
        LocalDateTime t0 = found.plusMinutes(10);
        // seq0 建档（草稿）；seq1 进入调查；seq2 封存——与真实状态机产出的修订序列同构
        revisionRepository.save(rev(event, 0, IncidentRevision.TYPE_CREATE, null,
                IncidentEvent.STATUS_DRAFT, zhao, "建立事件", null, fullContent, t0, 0));
        revisionRepository.save(rev(event, 1, IncidentRevision.TYPE_ENTER_INVESTIGATING,
                IncidentEvent.STATUS_DRAFT, IncidentEvent.STATUS_INVESTIGATING, zhao, null,
                fullContent, fullContent, t0.plusMinutes(5), 0));
        revisionRepository.save(rev(event, 2, IncidentRevision.TYPE_SEAL,
                IncidentEvent.STATUS_PENDING_SEAL, IncidentEvent.STATUS_SEALED, sun, "证据链闭合，同意封存",
                fullContent, fullContent, t0.plusHours(2), 1));

        sealRoundRepository.save(IncidentSealRound.builder()
                .incidentId(event.getId())
                .roundNo(1)
                .sealTime(t0.plusHours(2))
                .sealedById(sun.getId())
                .sealedByName(sun.getStaffName())
                .sealedVersion(3L)
                .build());
        log.info("种子：演示异常事件 {} 已写入（已封存第1轮，赵卫东/李向北可见，孙安全可查看全部）", DEMO_CODE);
    }

    private IncidentRevision rev(IncidentEvent e, int seq, String type, String from, String to,
                                 GroundStaff op, String reason, String before, String after,
                                 LocalDateTime time, int sealRound) {
        return IncidentRevision.builder()
                .incidentId(e.getId())
                .seq(seq)
                .revisionType(type)
                .fromStatus(from)
                .toStatus(to)
                .operatorId(op.getId())
                .operatorName(op.getStaffName())
                .operatorRole(op.getStaffRole())
                .reason(reason)
                .beforeContent(before)
                .afterContent(after)
                .sealRound(sealRound)
                .operateTime(time)
                .build();
    }
}
