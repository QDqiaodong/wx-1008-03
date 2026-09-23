package com.px.base.service;

import com.px.base.dto.QualificationView;
import com.px.base.dto.RouteWatchEntryDTO;
import com.px.base.dto.WatchUpsertDTO;
import com.px.base.dto.WatchViewDTO;
import com.px.base.entity.FlightRoute;
import com.px.base.entity.FlightWatch;
import com.px.base.entity.GroundStaff;
import com.px.base.repository.FlightRouteRepository;
import com.px.base.repository.FlightWatchRepository;
import com.px.base.repository.GroundStaffRepository;
import com.px.base.rule.Csv;
import com.px.base.rule.WatchPolicy;
import com.px.base.security.CurrentUser;
import com.px.base.security.CurrentUserResolver;
import com.px.base.security.ForbiddenException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class FlightWatchService {

    private final FlightWatchRepository watchRepository;
    private final FlightRouteRepository routeRepository;
    private final GroundStaffRepository staffRepository;
    private final CurrentUserResolver currentUserResolver;
    private final WatchRequalifier requalifier;

    public static String statusLabel(String status) {
        return switch (status) {
            case FlightWatch.STATUS_DRAFT -> "草拟";
            case FlightWatch.STATUS_PENDING_REVIEW -> "待复核";
            case FlightWatch.STATUS_READY -> "就绪";
            case FlightWatch.STATUS_CANCELLED -> "取消";
            default -> status;
        };
    }

    @Transactional
    public FlightWatch create(WatchUpsertDTO dto) {
        currentUserResolver.require();
        validateUpsert(dto);

        FlightRoute route = routeRepository.findById(dto.getRouteId())
                .orElseThrow(() -> new IllegalArgumentException("航线不存在: " + dto.getRouteId()));
        GroundStaff operator = requireActiveStaff(dto.getOperatorId(), "操作员");
        GroundStaff reviewer = requireActiveStaff(dto.getReviewerId(), "复核员");

        // 职责分离：操作员与复核员必须是不同人员，硬拒绝
        if (operator.getId().equals(reviewer.getId())) {
            throw new IllegalArgumentException(String.format(
                    "操作员与复核员不能为同一人：「%s」被同时安排为两个职责，已拒绝", operator.getStaffName()));
        }
        if (dto.getPlannedEnd() != null && dto.getPlannedEnd().isBefore(dto.getPlannedTakeoff())) {
            throw new IllegalArgumentException("预计结束时刻不能早于预计起飞时刻");
        }

        // 同航线同飞行日只允许一条未取消的安排
        LocalDateTime takeoff = dto.getPlannedTakeoff();
        List<FlightWatch> existing = watchRepository
                .findByRouteIdAndFlightDateAndStatusNot(route.getId(), takeoff.toLocalDate(),
                        FlightWatch.STATUS_CANCELLED);
        if (!existing.isEmpty()) {
            throw new IllegalArgumentException(String.format(
                    "航线%s在%s已有一条未取消的值守安排（当前状态：%s）",
                    route.getRouteCode(), takeoff.toLocalDate(), statusLabel(existing.get(0).getStatus())));
        }

        FlightWatch watch = FlightWatch.builder()
                .routeId(route.getId())
                .routeCode(route.getRouteCode())
                .flightDate(takeoff.toLocalDate())
                .plannedTakeoff(takeoff)
                .plannedEnd(dto.getPlannedEnd())
                .operatorId(operator.getId())
                .operatorName(operator.getStaffName())
                .reviewerId(reviewer.getId())
                .reviewerName(reviewer.getStaffName())
                .status(FlightWatch.STATUS_DRAFT)
                .operatorArrived(0)
                .reviewerArrived(0)
                .build();
        FlightWatch saved = watchRepository.save(watch);
        log.info("创建值守: 航线{} 飞行日{} 操作员{} 复核员{}",
                route.getRouteCode(), saved.getFlightDate(), operator.getStaffName(), reviewer.getStaffName());
        return saved;
    }

    /** 改派/改时刻：仅草拟态可改（待复核必须先撤回到位），已就绪/已取消为终态 */
    @Transactional
    public FlightWatch update(Long id, WatchUpsertDTO dto) {
        CurrentUser user = currentUserResolver.require();
        validateUpsert(dto);
        FlightWatch watch = getWatch(id);
        if (watch.isTerminal()) {
            throw new IllegalArgumentException(String.format(
                    "值守已处于「%s」终态，不能修改", statusLabel(watch.getStatus())));
        }
        if (FlightWatch.STATUS_PENDING_REVIEW.equals(watch.getStatus())) {
            throw new IllegalArgumentException("值守已进入待复核，请先由操作员撤回到位确认后再改派");
        }
        FlightRoute route = routeRepository.findById(dto.getRouteId())
                .orElseThrow(() -> new IllegalArgumentException("航线不存在: " + dto.getRouteId()));
        GroundStaff operator = requireActiveStaff(dto.getOperatorId(), "操作员");
        GroundStaff reviewer = requireActiveStaff(dto.getReviewerId(), "复核员");
        if (operator.getId().equals(reviewer.getId())) {
            throw new IllegalArgumentException(String.format(
                    "操作员与复核员不能为同一人：「%s」被同时安排为两个职责，已拒绝", operator.getStaffName()));
        }
        if (dto.getPlannedEnd() != null && dto.getPlannedEnd().isBefore(dto.getPlannedTakeoff())) {
            throw new IllegalArgumentException("预计结束时刻不能早于预计起飞时刻");
        }

        watch.setRouteId(route.getId());
        watch.setRouteCode(route.getRouteCode());
        watch.setPlannedTakeoff(dto.getPlannedTakeoff());
        watch.setFlightDate(dto.getPlannedTakeoff().toLocalDate());
        watch.setPlannedEnd(dto.getPlannedEnd());
        watch.setOperatorId(operator.getId());
        watch.setOperatorName(operator.getStaffName());
        watch.setReviewerId(reviewer.getId());
        watch.setReviewerName(reviewer.getStaffName());
        // 任何排班变化都清空旧的缺口提示，查看时实时重算
        watch.setRequalifyReason(null);
        log.info("值守[id={}]由{}改派: 航线{} 起飞{} 操作员{} 复核员{}",
                id, user.name(), route.getRouteCode(), dto.getPlannedTakeoff(),
                operator.getStaffName(), reviewer.getStaffName());
        return watchRepository.save(watch);
    }

    /**
     * 操作员确认现场到位。普通值班员只能为自己确认（服务端强制）。
     * 到位不校验资质——到位只是“人到现场”，能不能就绪由复核环节按资质判定。
     */
    @Transactional
    public FlightWatch operatorArrive(Long id) {
        CurrentUser user = currentUserResolver.require();
        FlightWatch watch = getWatch(id);
        if (watch.isTerminal()) {
            throw new IllegalArgumentException(String.format(
                    "值守已处于「%s」终态，不能再确认到位", statusLabel(watch.getStatus())));
        }
        if (!user.id().equals(watch.getOperatorId())) {
            throw new ForbiddenException(String.format(
                    "到位确认被拒绝：只有操作员本人「%s」能确认现场到位，当前操作人是「%s」",
                    watch.getOperatorName(), user.name()));
        }
        watch.setOperatorArrived(1);
        watch.setArrivalTime(LocalDateTime.now());
        // 操作员到位后进入待复核（无论资质是否齐备，状态链先前进，缺口在就绪环节拦住）
        watch.setStatus(FlightWatch.STATUS_PENDING_REVIEW);
        log.info("操作员{}确认到位，值守[id={}]进入待复核", user.name(), id);
        return watchRepository.save(watch);
    }

    /** 操作员撤回自己的到位确认（用于资质被重新判定打回后重做） */
    @Transactional
    public FlightWatch operatorWithdraw(Long id) {
        CurrentUser user = currentUserResolver.require();
        FlightWatch watch = getWatch(id);
        if (watch.isReady() || watch.isCancelled()) {
            throw new IllegalArgumentException("已就绪或已取消的值守不能撤回到位");
        }
        if (!user.id().equals(watch.getOperatorId()) && !user.isSafetyOfficer()) {
            throw new ForbiddenException(String.format(
                    "撤回被拒绝：只有操作员本人「%s」或安全主管能撤回，当前操作人是「%s」",
                    watch.getOperatorName(), user.name()));
        }
        watch.setOperatorArrived(0);
        watch.setArrivalTime(null);
        watch.setReviewerArrived(0);
        watch.setReviewerArrivalTime(null);
        watch.setStatus(FlightWatch.STATUS_DRAFT);
        return watchRepository.save(watch);
    }

    /**
     * 复核员确认就绪——状态链闸门：
     *  1) 只有复核员本人或安全主管可执行（普通值班员不能复核别人、也不能复核自己的活）；
     *  2) 操作员必须已确认到位；
     *  3) 两人资质按起飞时刻实时判定：风级 + 全部在用锚点区域，逐项缺口直接报错；
     *  4) 通过即冻结快照（证书编号/适用范围/姓名/风级/区域/时刻）。
     */
    @Transactional
    public FlightWatch reviewerReady(Long id) {
        CurrentUser user = currentUserResolver.require();
        FlightWatch watch = getWatch(id);
        if (watch.isReady()) {
            throw new IllegalArgumentException("值守已就绪，不能重复确认");
        }
        if (watch.isCancelled()) {
            throw new IllegalArgumentException("值守已取消，不能确认就绪");
        }
        // 复核职责分离：只有被排班的复核员本人能确认就绪。
        // 操作员不能复核自己的工作；安全主管也不代行复核（主管特权仅限吊销证书、
        // 取消已就绪值守），避免“主管万能”绕过双人复核。
        if (!user.id().equals(watch.getReviewerId())) {
            throw new ForbiddenException(String.format(
                    "就绪确认被拒绝：只有该班复核员「%s」本人能确认就绪，当前操作人「%s」不是复核员"
                            + "（安全主管也不能代行复核，只能取消已就绪值守）",
                    watch.getReviewerName(), user.name()));
        }
        if (watch.getOperatorArrived() == null || watch.getOperatorArrived() != 1) {
            throw new com.px.base.security.BusinessConflictException("操作员尚未确认现场到位，复核员不能确认就绪");
        }

        // 就绪前实时重算资质（此时吊销/到期/改风级/改锚点都会暴露）
        WatchRequalifier.RecheckResult recheck = requalifier.recheck(watch);
        if (!recheck.allQualified()) {
            throw new com.px.base.security.BusinessConflictException(String.format(
                    "资质不满足，不能就绪，逐项缺口：%s", String.join("；", recheck.gapMessages())));
        }

        GroundStaff operator = staffRepository.findById(watch.getOperatorId()).orElseThrow();
        GroundStaff reviewer = staffRepository.findById(watch.getReviewerId()).orElseThrow();
        FlightRoute route = routeRepository.findById(watch.getRouteId()).orElseThrow();
        List<String> zones = new ArrayList<>(recheck.requiredZones());

        watch.setReviewerArrived(1);
        watch.setReviewerArrivalTime(LocalDateTime.now());
        watch.setStatus(FlightWatch.STATUS_READY);
        watch.setReadyTime(LocalDateTime.now());
        watch.setReadiedById(user.id());
        watch.setReadiedByName(user.name());
        watch.setRequalifyReason(null);

        // 冻结就绪快照：此后人员改名、证书吊销/范围修改都不影响历史
        watch.setSnapshotTakeoff(watch.getPlannedTakeoff());
        watch.setSnapshotEnd(watch.getPlannedEnd());
        watch.setSnapshotRouteWindLevel(route.getWindLevel());
        watch.setSnapshotRequiredZones(Csv.join(zones));

        watch.setOperatorSnapshotName(operator.getStaffName());
        watch.setOperatorSnapshotCertNo(recheck.operator().activeCertNo());
        watch.setOperatorSnapshotScope(scopeSnapshot(recheck.operator()));

        watch.setReviewerSnapshotName(reviewer.getStaffName());
        watch.setReviewerSnapshotCertNo(recheck.reviewer().activeCertNo());
        watch.setReviewerSnapshotScope(scopeSnapshot(recheck.reviewer()));

        log.info("复核员{}确认值守[id={}]就绪，冻结证书快照 操作员证={} 复核员证={}",
                user.name(), id, watch.getOperatorSnapshotCertNo(), watch.getReviewerSnapshotCertNo());
        return watchRepository.save(watch);
    }

    /**
     * 取消值守：
     *  - 取消【已就绪】值守仅安全主管可操作；
     *  - 取消未就绪值守：操作员本人、复核员本人或安全主管可操作。
     * 权限全部服务端判定，绕过前端直接调用同样会被拒，且数据不变更。
     */
    @Transactional
    public FlightWatch cancel(Long id, String reason) {
        CurrentUser user = currentUserResolver.require();
        FlightWatch watch = getWatch(id);
        if (watch.isCancelled()) {
            throw new IllegalArgumentException("值守已处于取消状态");
        }
        boolean related = user.id().equals(watch.getOperatorId()) || user.id().equals(watch.getReviewerId());
        if (watch.isReady()) {
            if (!user.isSafetyOfficer()) {
                throw new ForbiddenException(String.format(
                        "取消被拒绝：已就绪值守只能由安全主管取消，当前操作人「%s」是普通值班员", user.name()));
            }
        } else {
            if (!user.isSafetyOfficer() && !related) {
                throw new ForbiddenException(String.format(
                        "取消被拒绝：只有本班操作员、复核员或安全主管可取消未就绪值守，「%s」不在其列",
                        user.name()));
            }
        }
        watch.setStatus(FlightWatch.STATUS_CANCELLED);
        watch.setCancelTime(LocalDateTime.now());
        watch.setCancelledById(user.id());
        watch.setCancelledByName(user.name());
        watch.setCancelReason(reason);
        log.warn("值守[id={}]由{}取消（就绪前状态={}），原因：{}", id, user.name(),
                watch.isReady() ? "就绪" : "未就绪", reason);
        return watchRepository.save(watch);
    }

    /* ---------------- 查询视图 ---------------- */

    public List<WatchViewDTO> listViews(Long routeId) {
        List<FlightWatch> watches = routeId == null
                ? watchRepository.findAllByOrderByFlightDateDescIdDesc()
                : watchRepository.findByRouteIdOrderByFlightDateDescIdDesc(routeId);
        return watches.stream().map(this::toView).toList();
    }

    public WatchViewDTO getView(Long id) {
        return toView(getWatch(id));
    }

    /** 航线入口：按日期列出每条航线的当日值守摘要与资格结论 */
    public List<RouteWatchEntryDTO> routeEntries(LocalDateTime dateContext) {
        LocalDateTime ctx = dateContext != null ? dateContext : LocalDateTime.now();
        return routeRepository.findAll().stream()
                .map(route -> {
                    List<String> anchorCodes = requalifier.activeAnchorCodes(route.getId());
                    Set<String> zones = requalifier.requiredZones(route.getId());

                    List<FlightWatch> watches = watchRepository
                            .findByRouteIdAndFlightDateAndStatusNot(route.getId(), ctx.toLocalDate(),
                                    FlightWatch.STATUS_CANCELLED);
                    FlightWatch watch = watches.isEmpty() ? null : watches.get(0);

                    RouteWatchEntryDTO.RouteWatchEntryDTOBuilder b = RouteWatchEntryDTO.builder()
                            .routeId(route.getId())
                            .routeCode(route.getRouteCode())
                            .routeName(route.getRouteName())
                            .windLevel(route.getWindLevel())
                            .windSpeed(route.getWindSpeed())
                            .status(route.getStatus())
                            .activeAnchorCodes(anchorCodes)
                            .requiredZones(zones.stream().sorted().toList())
                            .policy(WatchPolicy.TAKEOFF_POLICY);

                    if (watch != null) {
                        b.watchId(watch.getId())
                                .watchStatus(watch.getStatus())
                                .watchStatusLabel(statusLabel(watch.getStatus()))
                                .flightDate(watch.getFlightDate())
                                .operatorId(watch.getOperatorId())
                                .operatorName(displayOperatorName(watch))
                                .reviewerId(watch.getReviewerId())
                                .reviewerName(displayReviewerName(watch))
                                .distinctPeople(!watch.getOperatorId().equals(watch.getReviewerId()))
                                .operatorArrived(watch.getOperatorArrived())
                                .ready(watch.isReady());

                        if (watch.isReady()) {
                            // 历史/就绪态：以快照结论为准，不再重算
                            b.operatorQualified(true)
                                    .reviewerQualified(true)
                                    .gapMessages(List.of("已就绪（历史快照）：操作员证 "
                                            + watch.getOperatorSnapshotCertNo() + "，复核员证 "
                                            + watch.getReviewerSnapshotCertNo()));
                        } else {
                            WatchRequalifier.RecheckResult r = requalifier.recheck(watch);
                            b.operatorQualified(r.operator().qualified())
                                    .reviewerQualified(r.reviewer().qualified());
                            List<String> gaps = new ArrayList<>(r.gapMessages());
                            if (!watch.getOperatorId().equals(watch.getReviewerId())) {
                                if (watch.getOperatorArrived() == null || watch.getOperatorArrived() != 1) {
                                    gaps.add("操作员尚未确认现场到位");
                                }
                            }
                            if (watch.getRequalifyReason() != null && !watch.getRequalifyReason().isBlank()) {
                                gaps.add("重新判定提示：" + watch.getRequalifyReason());
                            }
                            b.gapMessages(gaps);
                        }
                    }
                    return b.build();
                })
                .toList();
    }

    private WatchViewDTO toView(FlightWatch watch) {
        FlightRoute route = routeRepository.findById(watch.getRouteId()).orElse(null);
        boolean terminal = watch.isTerminal();

        WatchViewDTO.WatchViewDTOBuilder b = WatchViewDTO.builder()
                .id(watch.getId())
                .routeId(watch.getRouteId())
                .routeCode(watch.getRouteCode())
                .routeName(route != null ? route.getRouteName() : watch.getRouteCode())
                .flightDate(watch.getFlightDate())
                .plannedTakeoff(watch.getPlannedTakeoff())
                .plannedEnd(watch.getPlannedEnd())
                .status(watch.getStatus())
                .statusLabel(statusLabel(watch.getStatus()))
                .operatorArrived(watch.getOperatorArrived())
                .arrivalTime(watch.getArrivalTime())
                .reviewerArrived(watch.getReviewerArrived())
                .reviewerArrivalTime(watch.getReviewerArrivalTime())
                .readyTime(watch.getReadyTime())
                .readiedByName(watch.getReadiedByName())
                .cancelTime(watch.getCancelTime())
                .cancelledByName(watch.getCancelledByName())
                .cancelReason(watch.getCancelReason())
                .requalifyReason(watch.getRequalifyReason())
                .operatorId(watch.getOperatorId())
                .reviewerId(watch.getReviewerId())
                .distinctPeople(!watch.getOperatorId().equals(watch.getReviewerId()))
                .past(watch.getPlannedTakeoff() != null && watch.getPlannedTakeoff().isBefore(LocalDateTime.now()))
                .policy(WatchPolicy.TAKEOFF_POLICY);

        if (terminal) {
            boolean hasSnapshot = watch.getOperatorSnapshotCertNo() != null;
            b.hasSnapshot(hasSnapshot)
                    .snapshotTakeoff(watch.getSnapshotTakeoff())
                    .snapshotEnd(watch.getSnapshotEnd())
                    .snapshotRouteWindLevel(watch.getSnapshotRouteWindLevel())
                    .snapshotRequiredZones(Csv.split(watch.getSnapshotRequiredZones()))
                    .operatorName(displayOperatorName(watch))
                    .reviewerName(displayReviewerName(watch))
                    .operatorSnapshotName(watch.getOperatorSnapshotName())
                    .operatorSnapshotCertNo(watch.getOperatorSnapshotCertNo())
                    .operatorSnapshotScope(watch.getOperatorSnapshotScope())
                    .reviewerSnapshotName(watch.getReviewerSnapshotName())
                    .reviewerSnapshotCertNo(watch.getReviewerSnapshotCertNo())
                    .reviewerSnapshotScope(watch.getReviewerSnapshotScope())
                    .canReady(false);
        } else {
            WatchRequalifier.RecheckResult r = requalifier.recheck(watch);
            b.operatorName(watch.getOperatorName())
                    .reviewerName(watch.getReviewerName())
                    .operatorQualification(toQualificationView(r.operator()))
                    .reviewerQualification(toQualificationView(r.reviewer()))
                    .requiredWindLevel(r.requiredWind())
                    .requiredZones(r.requiredZones().stream().sorted().toList())
                    .hasSnapshot(false)
                    .canReady((watch.getOperatorArrived() != null && watch.getOperatorArrived() == 1)
                            && r.allQualified()
                            && !watch.getOperatorId().equals(watch.getReviewerId()));
        }
        return b.build();
    }

    /** 历史态显示就绪当时冻结的姓名；非终态显示当前排班姓名 */
    private String displayOperatorName(FlightWatch watch) {
        return watch.isTerminal() && watch.getOperatorSnapshotName() != null
                ? watch.getOperatorSnapshotName() : watch.getOperatorName();
    }

    private String displayReviewerName(FlightWatch watch) {
        return watch.isTerminal() && watch.getReviewerSnapshotName() != null
                ? watch.getReviewerSnapshotName() : watch.getReviewerName();
    }

    private String scopeSnapshot(com.px.base.rule.QualificationResult q) {
        return String.format("适用风级[%s]；可负责区域[%s]",
                String.join("、", q.coveredWindLevels()),
                q.coveredZones().isEmpty() ? "无" : String.join("、", q.coveredZones()));
    }

    private QualificationView toQualificationView(com.px.base.rule.QualificationResult q) {
        return QualificationView.builder()
                .staffId(q.staffId())
                .staffName(q.staffName())
                .qualified(q.qualified())
                .activeCertNo(q.activeCertNo())
                .activeCertStatus(q.activeCertStatus())
                .coveredWindLevels(q.coveredWindLevels())
                .coveredZones(q.coveredZones())
                .missingWindLevels(q.missingWindLevels())
                .missingZones(q.missingZones())
                .detailMessages(q.detailMessages())
                .build();
    }

    private FlightWatch getWatch(Long id) {
        return watchRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("值守安排不存在: " + id));
    }

    private GroundStaff requireActiveStaff(Long id, String roleLabel) {
        GroundStaff staff = staffRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(roleLabel + "人员不存在: " + id));
        if (staff.getStatus() == null || staff.getStatus() != 1) {
            throw new IllegalArgumentException(roleLabel + "「" + staff.getStaffName() + "」已停用，不能排班");
        }
        return staff;
    }

    private void validateUpsert(WatchUpsertDTO dto) {
        if (dto.getRouteId() == null) {
            throw new IllegalArgumentException("必须选择航线");
        }
        if (dto.getOperatorId() == null || dto.getReviewerId() == null) {
            throw new IllegalArgumentException("必须同时安排操作员与复核员");
        }
        if (dto.getPlannedTakeoff() == null) {
            throw new IllegalArgumentException("必须填写预计起飞时刻（跨午夜任务按起飞时刻判定证书）");
        }
    }
}
