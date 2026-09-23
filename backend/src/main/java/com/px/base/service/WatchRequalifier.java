package com.px.base.service;

import com.px.base.entity.Anchor;
import com.px.base.entity.FlightRoute;
import com.px.base.entity.FlightWatch;
import com.px.base.entity.RouteAnchor;
import com.px.base.repository.AnchorRepository;
import com.px.base.repository.FlightRouteRepository;
import com.px.base.repository.FlightWatchRepository;
import com.px.base.repository.GroundCertRepository;
import com.px.base.repository.RouteAnchorRepository;
import com.px.base.rule.QualificationEvaluator;
import com.px.base.rule.QualificationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 值守重新判定器。
 *
 * 触发点：证书被吊销（立即扫描）。证书到期、航线风级变化、锚点区域变化、
 * 改飞行时刻则走“读时重算”——任何非终态值守在查看时都按当前数据重新评估，
 * 不需要定时器，且结论天然一致。
 *
 * 硬规则：只处理【飞行日在今天之后】且【尚未进入终态】的安排；
 * READY（已就绪）与 CANCELLED（已取消）永不改动，历史快照保持有效。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WatchRequalifier {

    private final FlightWatchRepository watchRepository;
    private final FlightRouteRepository routeRepository;
    private final RouteAnchorRepository routeAnchorRepository;
    private final AnchorRepository anchorRepository;
    private final GroundCertRepository certRepository;

    /**
     * 证书吊销后立即重判受影响人员的未来未就绪值守。
     *
     * @return 被打回草拟的值守数量
     */
    @Transactional
    public int requalifyAfterRevocation(Long staffId, String certNo) {
        LocalDate today = LocalDate.now();
        List<FlightWatch> candidates = watchRepository.findByFlightDateAfterAndStatusIn(
                today, List.of(FlightWatch.STATUS_DRAFT, FlightWatch.STATUS_PENDING_REVIEW));

        int affected = 0;
        for (FlightWatch watch : candidates) {
            boolean involves = staffId.equals(watch.getOperatorId()) || staffId.equals(watch.getReviewerId());
            // 双保险：终态（就绪/取消）永不参与重判定，哪怕仓储查询意外带入
            if (!involves || watch.isTerminal()) {
                continue;
            }
            RecheckResult result = recheck(watch);
            if (!result.allQualified()) {
                boolean demoted = FlightWatch.STATUS_PENDING_REVIEW.equals(watch.getStatus());
                watch.setStatus(FlightWatch.STATUS_DRAFT);
                // 打回草拟后两人到位确认全部作废，必须重新到位、重新复核
                watch.setOperatorArrived(0);
                watch.setArrivalTime(null);
                watch.setReviewerArrived(0);
                watch.setReviewerArrivalTime(null);
                watch.setRequalifyReason(String.format(
                        "证书%s于本飞行日已不适用（已吊销），重新判定：%s。值守打回草拟，需重新确认到位与资质。",
                        certNo, String.join("；", result.gapMessages())));
                watchRepository.save(watch);
                affected++;
                log.info("值守[id={}]因证书{}吊销被打回草拟（原状态:{}）", watch.getId(), certNo,
                        demoted ? "待复核" : "草拟");
            }
        }
        return affected;
    }

    /** 读时重算：返回当前两人资质结论，供视图与就绪动作实时判定。 */
    public RecheckResult recheck(FlightWatch watch) {
        FlightRoute route = routeRepository.findById(watch.getRouteId()).orElse(null);
        String requiredWind = route != null ? route.getWindLevel() : null;
        Set<String> requiredZones = requiredZones(watch.getRouteId());

        QualificationResult operator = QualificationEvaluator.evaluate(
                watch.getOperatorId(), watch.getOperatorName(),
                certRepository.findByStaffId(watch.getOperatorId()),
                requiredWind, requiredZones, watch.getPlannedTakeoff());

        QualificationResult reviewer = QualificationEvaluator.evaluate(
                watch.getReviewerId(), watch.getReviewerName(),
                certRepository.findByStaffId(watch.getReviewerId()),
                requiredWind, requiredZones, watch.getPlannedTakeoff());

        List<String> gaps = new java.util.ArrayList<>();
        gaps.addAll(operator.detailMessages());
        gaps.addAll(reviewer.detailMessages());
        return new RecheckResult(operator, reviewer, requiredWind, requiredZones, gaps);
    }

    /** 航线当前全部在用锚点（绑定中）所涉及的区域去重集合 */
    public Set<String> requiredZones(Long routeId) {
        Set<String> zones = new HashSet<>();
        for (RouteAnchor ra : routeAnchorRepository.findByRouteIdAndStatus(routeId, 1)) {
            anchorRepository.findById(ra.getAnchorId())
                    .filter(a -> a.getStatus() != null && a.getStatus() == 1)
                    .map(Anchor::getAnchorZone)
                    .filter(z -> z != null && !z.isBlank())
                    .ifPresent(z -> zones.add(z.trim()));
        }
        return zones;
    }

    /** 当前在用锚点编号（航线入口展示用） */
    public List<String> activeAnchorCodes(Long routeId) {
        return routeAnchorRepository.findByRouteIdAndStatus(routeId, 1).stream()
                .map(ra -> anchorRepository.findById(ra.getAnchorId()).orElse(null))
                .filter(a -> a != null && a.getStatus() != null && a.getStatus() == 1)
                .map(Anchor::getAnchorCode)
                .toList();
    }

    public record RecheckResult(QualificationResult operator, QualificationResult reviewer,
                                String requiredWind, Set<String> requiredZones,
                                List<String> gapMessages) {
        public boolean allQualified() {
            return operator.qualified() && reviewer.qualified();
        }
    }
}
