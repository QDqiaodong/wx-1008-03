package com.px.base.config;

import com.px.base.entity.Anchor;
import com.px.base.entity.FlightRoute;
import com.px.base.entity.FlightWatch;
import com.px.base.entity.GroundCert;
import com.px.base.entity.GroundStaff;
import com.px.base.repository.AnchorRepository;
import com.px.base.repository.FlightRouteRepository;
import com.px.base.repository.FlightWatchRepository;
import com.px.base.repository.GroundCertRepository;
import com.px.base.repository.GroundStaffRepository;
import com.px.base.rule.Csv;
import com.px.base.service.AdaptService;
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
 * 地勤资质与开航值守演示/验收种子（仅 local profile 由 DemoDataSeeder 所在 profile 控制；
 * 本 Runner 无条件幂等执行，全部按编号判重）。
 *
 * 覆盖验收所需样本：
 *  - 同一人两证（东区全能 / 仅西区）、待生效证、过期证、吊销证；
 *  - 一条【昨天已结束且就绪】的值守，带冻结快照（改名/吊销后历史不变）；
 *  - 一条【明天未来】的值守，处于草拟且资质不齐（吊销证后应重新判定）。
 */
@Component
@Order(20)
@RequiredArgsConstructor
@Slf4j
public class GroundDemoSeeder implements CommandLineRunner {

    private final AnchorRepository anchorRepository;
    private final FlightRouteRepository routeRepository;
    private final GroundStaffRepository staffRepository;
    private final GroundCertRepository certRepository;
    private final FlightWatchRepository watchRepository;
    private final AdaptService adaptService;

    @Override
    @Transactional
    public void run(String... args) {
        assignZones();
        bindSampleAnchors();
        seedStaffAndCerts();
        seedWatches();
    }

    private void assignZones() {
        updateZone("A-GOOD-2000", "中区");
        updateZone("A-MID-900", "东区");
        updateZone("A-SF600", "西区");
        updateZone("A-WEAK400", "东区");
        updateZone("A-GALE2600", "北区");
        updateZone("A-SCARCE-1800", "南区");
    }

    private void updateZone(String code, String zone) {
        anchorRepository.findByAnchorCode(code).ifPresent(a -> {
            if (a.getAnchorZone() == null || a.getAnchorZone().isBlank()) {
                a.setAnchorZone(zone);
                anchorRepository.save(a);
            }
        });
    }

    /** 给航线绑定在用锚点（幂等：已绑定跳过），产生“在用锚点区域”样本 */
    private void bindSampleAnchors() {
        bindIfNeeded("R-WEAK", "A-WEAK400");
        bindIfNeeded("R-LIGHT", "A-MID-900");
        bindIfNeeded("R-STRONG", "A-SF600");
        bindIfNeeded("R-GALE", "A-GALE2600");
    }

    private void bindIfNeeded(String routeCode, String anchorCode) {
        FlightRoute route = routeRepository.findByRouteCode(routeCode).orElse(null);
        Anchor anchor = anchorRepository.findByAnchorCode(anchorCode).orElse(null);
        if (route == null || anchor == null) {
            return;
        }
        boolean already = adaptService.getBoundAnchors(route.getId()).stream()
                .anyMatch(ra -> ra.getAnchorId().equals(anchor.getId()));
        if (!already) {
            adaptService.bindAnchor(route.getId(), anchor.getId());
        }
    }

    private void seedStaffAndCerts() {
        GroundStaff zhao = staff("S001", "赵卫东", GroundStaff.ROLE_STATION_OFFICER);
        GroundStaff qian = staff("S002", "钱立群", GroundStaff.ROLE_STATION_OFFICER);
        GroundStaff sun = staff("S003", "孙安全", GroundStaff.ROLE_SAFETY_OFFICER);
        GroundStaff li = staff("S004", "李向北", GroundStaff.ROLE_STATION_OFFICER);

        LocalDate today = LocalDate.now();
        // 赵：东区全能证（覆盖东/中/南/北区，全风级），长期有效
        cert("C-ZHAO-ALL", zhao.getId(), List.of("微风", "轻风", "和风", "强风", "疾风"),
                List.of("东区", "中区", "南区", "北区"), today.minusDays(30), today.plusDays(300), false);
        // 赵：另一张仅西区的证（用于演示“部分区域”时不会被它救场）
        cert("C-ZHAO-WEST", zhao.getId(), List.of("强风", "疾风"),
                List.of("西区"), today.minusDays(10), today.plusDays(100), false);

        // 钱：只覆盖微风/轻风、只有东区（配 R-STRONG 会缺风级；配跨区航线会缺区域）
        cert("C-QIAN-PART", qian.getId(), List.of("微风", "轻风"),
                List.of("东区"), today.minusDays(20), today.plusDays(200), false);
        // 钱：一张已吊销的全能证（吊销永久无效）
        cert("C-QIAN-REVOKED", qian.getId(), List.of("微风", "轻风", "和风", "强风", "疾风"),
                List.of("东区", "西区", "南区", "北区", "中区"), today.minusYears(1), today.plusYears(1), true);

        // 孙（安全主管）：有效全能证
        cert("C-SUN-ALL", sun.getId(), List.of("微风", "轻风", "和风", "强风", "疾风"),
                List.of("东区", "西区", "南区", "北区", "中区"), today.minusDays(5), today.plusDays(365), false);

        // 李：北区证（覆盖 R-GALE），但另有一张过期的全风级证
        cert("C-LI-NORTH", li.getId(), List.of("微风", "轻风", "和风", "强风", "疾风"),
                List.of("北区"), today.minusDays(15), today.plusDays(150), false);
        cert("C-LI-EXPIRED", li.getId(), List.of("微风", "轻风", "和风", "强风", "疾风"),
                List.of("东区", "西区"), today.minusYears(2), today.minusDays(1), false);
        // 李：待生效证（未来日期才有效）
        cert("C-LI-FUTURE", li.getId(), List.of("微风", "轻风", "和风", "强风", "疾风"),
                List.of("东区", "西区", "南区", "北区", "中区"), today.plusDays(40), today.plusDays(400), false);
    }

    private void seedWatches() {
        GroundStaff zhao = staffRepository.findByStaffCode("S001").orElseThrow();
        GroundStaff qian = staffRepository.findByStaffCode("S002").orElseThrow();
        GroundStaff li = staffRepository.findByStaffCode("S004").orElseThrow();
        GroundCert zhaoCert = certRepository.findByCertNo("C-ZHAO-ALL").orElseThrow();
        GroundCert liCert = certRepository.findByCertNo("C-LI-NORTH").orElseThrow();

        LocalDate yesterday = LocalDate.now().minusDays(1);
        FlightRoute gale = routeRepository.findByRouteCode("R-GALE").orElseThrow();

        // 历史值守：昨天 23:30 起飞、今天 01:00 结束（跨午夜），已就绪并冻结快照
        if (watchRepository.findByRouteIdOrderByFlightDateDescIdDesc(gale.getId()).stream()
                .noneMatch(w -> w.getFlightDate().equals(yesterday)
                        && FlightWatch.STATUS_READY.equals(w.getStatus()))) {
            LocalDateTime takeoff = yesterday.atTime(23, 30);
            FlightWatch past = FlightWatch.builder()
                    .routeId(gale.getId())
                    .routeCode(gale.getRouteCode())
                    .flightDate(yesterday)
                    .plannedTakeoff(takeoff)
                    .plannedEnd(yesterday.plusDays(1).atTime(1, 0))
                    .operatorId(zhao.getId())
                    .operatorName(zhao.getStaffName())
                    .reviewerId(li.getId())
                    .reviewerName(li.getStaffName())
                    .status(FlightWatch.STATUS_READY)
                    .operatorArrived(1)
                    .arrivalTime(takeoff.minusHours(1))
                    .reviewerArrived(1)
                    .reviewerArrivalTime(takeoff.minusMinutes(40))
                    .readyTime(takeoff.minusMinutes(30))
                    .readiedById(li.getId())
                    .readiedByName(li.getStaffName())
                    .snapshotTakeoff(takeoff)
                    .snapshotEnd(yesterday.plusDays(1).atTime(1, 0))
                    .snapshotRouteWindLevel(gale.getWindLevel())
                    .snapshotRequiredZones(Csv.join(List.of("北区")))
                    .operatorSnapshotName(zhao.getStaffName())
                    .operatorSnapshotCertNo(zhaoCert.getCertNo())
                    .operatorSnapshotScope("适用风级[微风、轻风、和风、强风、疾风]；可负责区域[东区、中区、南区、北区]")
                    .reviewerSnapshotName(li.getStaffName())
                    .reviewerSnapshotCertNo(liCert.getCertNo())
                    .reviewerSnapshotScope("适用风级[微风、轻风、和风、强风、疾风]；可负责区域[北区]")
                    .build();
            watchRepository.save(past);
            log.info("种子：已就绪跨午夜历史值守已写入（飞行日{}，快照已冻结）", yesterday);
        }

        // 未来值守：明天 R-STRONG（西区，强风），赵(有强风但无西区资格? 赵有 C-ZHAO-WEST 覆盖强风+西区)→赵合格；
        // 钱只有东区微风/轻风 → 风级与区域双缺。值守置为草拟，用于演示逐项缺口。
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        FlightRoute strong = routeRepository.findByRouteCode("R-STRONG").orElseThrow();
        if (watchRepository.findByRouteIdOrderByFlightDateDescIdDesc(strong.getId()).stream()
                .noneMatch(w -> w.getFlightDate().equals(tomorrow))) {
            LocalDateTime takeoff = tomorrow.atTime(9, 0);
            FlightWatch future = FlightWatch.builder()
                    .routeId(strong.getId())
                    .routeCode(strong.getRouteCode())
                    .flightDate(tomorrow)
                    .plannedTakeoff(takeoff)
                    .plannedEnd(tomorrow.atTime(11, 0))
                    .operatorId(qian.getId())
                    .operatorName(qian.getStaffName())
                    .reviewerId(zhao.getId())
                    .reviewerName(zhao.getStaffName())
                    .status(FlightWatch.STATUS_DRAFT)
                    .operatorArrived(0)
                    .reviewerArrived(0)
                    .build();
            watchRepository.save(future);
            log.info("种子：未来草拟值守已写入（飞行日{}，钱缺强风/西区资格）", tomorrow);
        }
    }

    private GroundStaff staff(String code, String name, String role) {
        return staffRepository.findByStaffCode(code).orElseGet(() ->
                staffRepository.save(GroundStaff.builder()
                        .staffCode(code)
                        .staffName(name)
                        .staffRole(role)
                        .status(1)
                        .build()));
    }

    private void cert(String no, Long staffId, List<String> winds, List<String> zones,
                      LocalDate effective, LocalDate expiry, boolean revoked) {
        if (certRepository.existsByCertNo(no)) {
            return;
        }
        GroundCert.GroundCertBuilder b = GroundCert.builder()
                .certNo(no)
                .staffId(staffId)
                .windLevels(Csv.join(winds))
                .anchorZones(Csv.join(zones))
                .effectiveDate(effective)
                .expiryDate(expiry)
                .revoked(revoked ? 1 : 0);
        if (revoked) {
            b.revokeTime(LocalDateTime.now().minusDays(30))
                    .revokeReason("演示数据：违规作业吊销")
                    .revokedByName("孙安全");
        }
        certRepository.save(b.build());
    }
}
