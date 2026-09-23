package com.px.base.config;

import com.px.base.entity.Anchor;
import com.px.base.entity.FlightRoute;
import com.px.base.entity.FlightWatch;
import com.px.base.entity.GroundStaff;
import com.px.base.incident.entity.Incident;
import com.px.base.incident.entity.IncidentAnchor;
import com.px.base.incident.entity.IncidentRevision;
import com.px.base.incident.repository.IncidentAnchorRepository;
import com.px.base.incident.repository.IncidentRepository;
import com.px.base.incident.repository.IncidentRevisionRepository;
import com.px.base.repository.AnchorRepository;
import com.px.base.repository.FlightRouteRepository;
import com.px.base.repository.FlightWatchRepository;
import com.px.base.repository.GroundStaffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 飞行异常事件演示种子（幂等，按事件编号判重）：
 * 以 GroundDemoSeeder 建好的“昨日 R-GALE 已就绪跨午夜值守 + 赵/李 + A-GALE2600”
 * 建一条【调查中】异常事件，冻结事发快照，并写入两版修订，
 * 方便验收时直接演示：继续补结论 → 提交封存 → 主管封存 → 更正/重开，以及改名/改锚点后对比差异。
 */
@Component
@Order(30)
@RequiredArgsConstructor
@Slf4j
public class IncidentDemoSeeder implements CommandLineRunner {

    private static final String DEMO_NO = "INC-DEMO-0001";
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final IncidentRepository incidentRepository;
    private final IncidentRevisionRepository revisionRepository;
    private final IncidentAnchorRepository incidentAnchorRepository;
    private final FlightRouteRepository routeRepository;
    private final FlightWatchRepository watchRepository;
    private final GroundStaffRepository staffRepository;
    private final AnchorRepository anchorRepository;

    @Override
    @Transactional
    public void run(String... args) {
        if (incidentRepository.existsByIncidentNo(DEMO_NO)) {
            return;
        }
        FlightRoute gale = routeRepository.findByRouteCode("R-GALE").orElse(null);
        Anchor galeAnchor = anchorRepository.findByAnchorCode("A-GALE2600").orElse(null);
        GroundStaff zhao = staffRepository.findByStaffCode("S001").orElse(null);
        GroundStaff li = staffRepository.findByStaffCode("S004").orElse(null);
        if (gale == null || galeAnchor == null || zhao == null || li == null) {
            return;
        }
        LocalDate yesterday = LocalDate.now().minusDays(1);
        FlightWatch watch = watchRepository
                .findByRouteIdOrderByFlightDateDescIdDesc(gale.getId()).stream()
                .filter(w -> w.getFlightDate().equals(yesterday)
                        && FlightWatch.STATUS_READY.equals(w.getStatus()))
                .findFirst().orElse(null);
        if (watch == null) {
            return;
        }

        LocalDateTime found = yesterday.atTime(23, 50);
        Incident inc = Incident.builder()
                .incidentNo(DEMO_NO)
                .title("演示：R-GALE 夜航降落后发现 A-GALE2600 锚点地脚螺栓松动")
                .watchId(watch.getId())
                .routeId(gale.getId())
                .foundTime(found)
                .severity(Incident.SEVERITY_MAJOR)
                .incidentNote("23:50 巡场时发现该锚点地脚螺栓松动约两圈，现场风速 16m/s（疾风）。"
                        + "操作员赵卫东、复核员李向北现场复核，立即停用并重新紧固。")
                .handlingAction("1. 立即停用 A-GALE2600；2. 重新紧固并划线标记；3. 暂停同批次锚点夜航。")
                .evidenceDesc("现场照片 EV-DEMO-01/02；巡检本 P12；赵、李双人签字记录。")
                .reporterId(zhao.getId())
                .reporterName(zhao.getStaffName())
                .status(Incident.STATUS_INVESTIGATING)
                .version(2)
                .sealedCount(0)
                .snapshotRouteCode(gale.getRouteCode())
                .snapshotRouteName(gale.getRouteName())
                .snapshotRouteWindLevel(gale.getWindLevel())
                .snapshotRouteGroup(gale.getRouteGroup())
                .snapshotWatchFlightDate(watch.getFlightDate().format(D))
                .snapshotWatchTakeoff(watch.getPlannedTakeoff().format(DT))
                .snapshotOperatorName(zhao.getStaffName())
                .snapshotReviewerName(li.getStaffName())
                .build();
        Incident saved = incidentRepository.save(inc);

        incidentAnchorRepository.save(IncidentAnchor.builder()
                .incidentId(saved.getId())
                .anchorId(galeAnchor.getId())
                .anchorCode(galeAnchor.getAnchorCode())
                .locationDesc(galeAnchor.getLocationDesc())
                .anchorZone(galeAnchor.getAnchorZone())
                .statusSnapshot(galeAnchor.getStatus() != null && galeAnchor.getStatus() == 1 ? "启用" : "停用")
                .maxWeight(galeAnchor.getMaxWeight())
                .build());

        // v1 建立
        IncidentRevision create = IncidentRevision.builder()
                .incidentId(saved.getId())
                .revisionNo(1)
                .changeType(IncidentRevision.TYPE_CREATE)
                .title(saved.getTitle())
                .foundTime(found)
                .severity(Incident.SEVERITY_MAJOR)
                .incidentNote("夜航结束后初步记录：锚点松动。")
                .handlingAction("停用并重新紧固。")
                .evidenceDesc(saved.getEvidenceDesc())
                .statusBefore(null)
                .statusAfter(Incident.STATUS_DRAFT)
                .operatorId(zhao.getId())
                .operatorName(zhao.getStaffName())
                .build();
        revisionRepository.save(create);

        // v2 开始调查（与当前调查中状态一致）
        IncidentRevision investigate = IncidentRevision.builder()
                .incidentId(saved.getId())
                .revisionNo(2)
                .changeType(IncidentRevision.TYPE_EDIT)
                .changeReason("开始调查，后续修订全部留痕")
                .title(saved.getTitle())
                .foundTime(found)
                .severity(Incident.SEVERITY_MAJOR)
                .incidentNote(saved.getIncidentNote())
                .handlingAction(saved.getHandlingAction())
                .evidenceDesc(saved.getEvidenceDesc())
                .statusBefore(Incident.STATUS_DRAFT)
                .statusAfter(Incident.STATUS_INVESTIGATING)
                .operatorId(li.getId())
                .operatorName(li.getStaffName())
                .build();
        investigate = revisionRepository.save(investigate);
        saved.setCurrentRevisionId(investigate.getId());
        incidentRepository.save(saved);

        log.info("种子：演示异常事件 {} 已写入（调查中，v2）", DEMO_NO);
    }
}
