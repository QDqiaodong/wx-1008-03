package com.px.base.incident.service;

import com.px.base.entity.Anchor;
import com.px.base.entity.FlightRoute;
import com.px.base.entity.FlightWatch;
import com.px.base.entity.GroundStaff;
import com.px.base.incident.dto.IncidentAnchorViewDTO;
import com.px.base.incident.dto.IncidentConflictDTO;
import com.px.base.incident.dto.IncidentCreateDTO;
import com.px.base.incident.dto.IncidentDetailDTO;
import com.px.base.incident.dto.IncidentEditDTO;
import com.px.base.incident.dto.IncidentListItemDTO;
import com.px.base.incident.dto.IncidentRevisionDTO;
import com.px.base.incident.dto.IncidentVersionConflictException;
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
import com.px.base.security.CurrentUser;
import com.px.base.security.CurrentUserResolver;
import com.px.base.security.BusinessConflictException;
import com.px.base.security.ForbiddenException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 飞行异常事件复盘。
 *
 * 并发口径（贯穿详情页/修订记录/持久化）：整份事件版本冲突。
 * 每个写请求必须携带 expectedVersion；服务端在同一事务内先对事件行加悲观写锁，
 * 再比对 version：不一致则整体拒绝（409），返回冲突字段与最新版本正文，
 * 由人决定放弃或基于最新内容重新编辑——绝不静默覆盖。
 *
 * 不采用按字段合并的原因：
 *  1) 事件正文是一份完整调查报告，现场经过、原因结论、纠正措施、负责人、期限互为前提；
 *     字段级自动合并会拼出“甲改的经过 + 乙删的结论 + 丙换的负责人”这种语义撕裂、
 *     无人审阅过的报告，事故调查不接受机器自动拼接；
 *  2) 封存闸门要求四项结论作为整体齐备且一致，字段合并会让整体校验失去意义；
 *  3) 封存后更正必须“带理由、留旧版”，字段合并会使“更正了什么、依据哪一版”无法追溯。
 *     整份版本冲突把合并决策交还给人，虽然交互上多一步重编，但安全责任清晰。
 *
 * 不变量：
 *  - 每次状态变更/正文修订都在同一事务内 append 一条不可变 incident_revision；
 *  - 事发快照（航线名/风级、值守人员、锚点状态）建事件时冻结，永不修改；
 *  - 权限全部在服务端判定，直接请求后端同样受限。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentService {

    public static final String CONCURRENCY_POLICY =
            "并发口径：整份事件版本冲突（乐观版本号 + 行锁）。每次保存都必须携带你打开详情时看到的版本号；"
          + "若期间已被他人改过，保存会被整体拒绝并列出冲突字段与最新版本，由你决定放弃或基于最新内容重新编辑，系统不会静默合并。";

    public static final String FIELD_MERGE_REJECTED_REASON =
            "不采用按字段自动合并：事件正文（现场经过/原因结论/纠正措施/负责人/期限）是互为前提的完整调查报告，"
          + "字段级合并会拼出无人审阅过的语义撕裂版本，封存闸门要求四项结论整体齐备，且封存后更正须带理由留旧版，"
          + "自动合并会让“更正了什么、依据哪一版”无法追溯；因此把合并决策交还给人。";

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final IncidentRepository incidentRepository;
    private final IncidentAnchorRepository incidentAnchorRepository;
    private final IncidentRevisionRepository revisionRepository;
    private final FlightRouteRepository routeRepository;
    private final FlightWatchRepository watchRepository;
    private final AnchorRepository anchorRepository;
    private final GroundStaffRepository staffRepository;
    private final CurrentUserResolver currentUserResolver;

    /* ==================== 标签与字段元数据 ==================== */

    public static String statusLabel(String s) {
        return switch (s) {
            case Incident.STATUS_DRAFT -> "草稿";
            case Incident.STATUS_INVESTIGATING -> "调查中";
            case Incident.STATUS_PENDING_SEAL -> "待封存";
            case Incident.STATUS_SEALED -> "已封存";
            case Incident.STATUS_REOPENED -> "重新开启";
            default -> s;
        };
    }

    public static String severityLabel(String s) {
        return switch (s) {
            case Incident.SEVERITY_MINOR -> "一般";
            case Incident.SEVERITY_MAJOR -> "较大";
            case Incident.SEVERITY_CRITICAL -> "严重";
            default -> s;
        };
    }

    public static String revisionTypeLabel(String t) {
        return switch (t) {
            case IncidentRevision.TYPE_CREATE -> "建立事件";
            case IncidentRevision.TYPE_EDIT -> "调查修订";
            case IncidentRevision.TYPE_SEAL -> "封存";
            case IncidentRevision.TYPE_CORRECTION -> "封存后更正";
            case IncidentRevision.TYPE_REOPEN -> "重新开启";
            default -> t;
        };
    }

    /** 可并发编辑字段的中文名（冲突比对与修订差异都用这套口径） */
    private static final LinkedHashMap<String, String> FIELD_LABELS = new LinkedHashMap<>();
    static {
        FIELD_LABELS.put("title", "标题");
        FIELD_LABELS.put("foundTime", "发现时间");
        FIELD_LABELS.put("severity", "严重级别");
        FIELD_LABELS.put("incidentNote", "现场经过");
        FIELD_LABELS.put("handlingAction", "处置动作");
        FIELD_LABELS.put("evidenceDesc", "证据说明");
        FIELD_LABELS.put("rootCause", "原因结论");
        FIELD_LABELS.put("correctiveAction", "纠正措施");
        FIELD_LABELS.put("ownerId", "整改负责人");
        FIELD_LABELS.put("dueDate", "整改期限");
    }

    /* ==================== 建立事件（冻结快照 + 首版修订，同事务） ==================== */

    @Transactional
    public IncidentDetailDTO create(IncidentCreateDTO dto) {
        CurrentUser user = currentUserResolver.require();
        validateCreate(dto);

        FlightRoute route = routeRepository.findById(dto.getRouteId())
                .orElseThrow(() -> new IllegalArgumentException("关联航线不存在: " + dto.getRouteId()));
        FlightWatch watch = null;
        if (dto.getWatchId() != null) {
            watch = watchRepository.findById(dto.getWatchId())
                    .orElseThrow(() -> new IllegalArgumentException("关联值守安排不存在: " + dto.getWatchId()));
            if (!watch.getRouteId().equals(route.getId())) {
                throw new IllegalArgumentException(
                        "关联值守不属于所选航线：该值守属于航线 " + watch.getRouteCode());
            }
        }
        // 涉及锚点必须存在（状态不限制：事发时停用的锚点也可能是涉事对象，冻结其当时状态）
        List<Anchor> anchors;
        if (dto.getAnchorIds() == null || dto.getAnchorIds().isEmpty()) {
            anchors = List.of();
        } else {
            anchors = dto.getAnchorIds().stream().distinct().map(aid -> anchorRepository.findById(aid)
                    .orElseThrow(() -> new IllegalArgumentException("涉及锚点不存在: " + aid))).toList();
        }

        GroundStaff reporter = staffRepository.findById(user.id()).orElseThrow();
        String incidentNo = generateIncidentNo();

        Incident inc = Incident.builder()
                .incidentNo(incidentNo)
                .title(dto.getTitle().trim())
                .watchId(watch != null ? watch.getId() : null)
                .routeId(route.getId())
                .foundTime(dto.getFoundTime())
                .severity(dto.getSeverity())
                .incidentNote(dto.getIncidentNote().trim())
                .handlingAction(trimToNull(dto.getHandlingAction()))
                .evidenceDesc(trimToNull(dto.getEvidenceDesc()))
                .reporterId(reporter.getId())
                .reporterName(reporter.getStaffName())
                .status(Incident.STATUS_DRAFT)
                .version(1)
                .sealedCount(0)
                .build();
        // 冻结航线/值守快照：航线名/风级、值守操作员/复核员姓名（事发时），之后永不修改
        snapshotStore.freeze(inc, route, watch);

        Incident saved = incidentRepository.save(inc);

        // 冻结涉及锚点的事发状态快照（独立快照行，之后永不修改、删除）
        for (Anchor a : anchors) {
            incidentAnchorRepository.save(IncidentAnchor.builder()
                    .incidentId(saved.getId())
                    .anchorId(a.getId())
                    .anchorCode(a.getAnchorCode())
                    .locationDesc(a.getLocationDesc())
                    .anchorZone(a.getAnchorZone())
                    .statusSnapshot(a.getStatus() != null && a.getStatus() == 1 ? "启用" : "停用")
                    .maxWeight(a.getMaxWeight())
                    .build());
        }

        // 首版修订（CREATE），与建事件同事务；航线/值守/锚点快照在修订之外的表中冻结
        IncidentRevision rev = revisionRepository.save(baseRevisionBuilder(saved)
                .revisionNo(1)
                .changeType(IncidentRevision.TYPE_CREATE)
                .changeReason(null)
                .statusBefore(null)
                .statusAfter(Incident.STATUS_DRAFT)
                .operatorId(user.id())
                .operatorName(user.name())
                .build());
        saved.setCurrentRevisionId(rev.getId());
        incidentRepository.save(saved);

        log.info("异常事件已建立: {} 航线{} 值守{} 报告人{}",
                incidentNo, route.getRouteCode(), watch != null ? watch.getId() : "无", user.name());
        return detail(saved.getId());
    }

    /* ==================== 正文修订（调查中编辑 / 封存后更正） ==================== */

    @Transactional
    public IncidentDetailDTO edit(Long id, IncidentEditDTO dto) {
        CurrentUser user = currentUserResolver.require();
        requireExpectedVersion(dto.getExpectedVersion());
        Incident inc = lockOr404(id);
        checkViewPermission(user, inc);

        String type;
        if (inc.isSealed()) {
            // 封存后：正文与原证据不能直接覆盖，只能“带理由更正”，生成新修订、旧版保留
            if (!user.isSafetyOfficer() && !user.id().equals(inc.getReporterId())) {
                throw new ForbiddenException(String.format(
                        "封存后更正被拒绝：仅安全主管或报告人可提出更正，当前操作人「%s」无权", user.name()));
            }
            if (dto.getChangeReason() == null || dto.getChangeReason().isBlank()) {
                throw new IllegalArgumentException("封存后的更正必须填写更正理由，旧版本将原样保留");
            }
            if (!equalsText(dto.getEvidenceDesc(), inc.getEvidenceDesc())) {
                throw new IllegalArgumentException(
                        "证据说明在封存后不可修改（原证据不可覆盖）；如需补充新材料，请写入纠正措施或发起重新开启");
            }
            type = IncidentRevision.TYPE_CORRECTION;
        } else {
            if (!canEditContent(user, inc)) {
                throw new ForbiddenException(String.format(
                        "修订被拒绝：当前状态「%s」下只有报告人、关联值守操作员/复核员或安全主管可编辑，「%s」不在其列",
                        statusLabel(inc.getStatus()), user.name()));
            }
            // 草稿阶段仅报告人（与安全主管）补充；值守操作员/复核员的修订留痕从“开始调查”起
            if (Incident.STATUS_DRAFT.equals(inc.getStatus())
                    && !user.id().equals(inc.getReporterId()) && !user.isSafetyOfficer()) {
                throw new ForbiddenException("草稿阶段只允许报告人补充；开始调查后值守操作员/复核员可共同修订");
            }
            if (Incident.STATUS_PENDING_SEAL.equals(inc.getStatus())) {
                throw new BusinessConflictException(
                        "待封存状态不能直接改正文，请先由安全主管封存，或退回调查中再修订");
            }
            type = IncidentRevision.TYPE_EDIT;
        }

        // 版本口径先于字段校验：拿着旧版本的提交，应先看到冲突，而不是被字段错误挡住
        checkVersion(inc, dto.getExpectedVersion(), dto);
        validateEditPayload(dto);

        // 负责人姓名按当前档案解析并冻结进本版修订（与值守快照同一原则）
        String ownerName = null;
        if (dto.getOwnerId() != null) {
            ownerName = staffRepository.findById(dto.getOwnerId())
                    .orElseThrow(() -> new IllegalArgumentException("整改负责人不存在: " + dto.getOwnerId()))
                    .getStaffName();
        }

        applyEditableFields(inc, dto, ownerName);
        appendRevision(inc, type, trimToNull(dto.getChangeReason()), inc.getStatus(), inc.getStatus(), user);
        return detail(id);
    }

    /* ==================== 状态流转（每一步都 append 修订，同事务） ==================== */

    /** 草稿 → 调查中（草稿允许报告人补充；开始调查后修订全部留痕） */
    @Transactional
    public IncidentDetailDTO startInvestigation(Long id, IncidentStatusActionDTO action) {
        CurrentUser user = currentUserResolver.require();
        requireExpectedVersion(action.getExpectedVersion());
        Incident inc = lockOr404(id);
        checkViewPermission(user, inc);
        if (!Incident.STATUS_DRAFT.equals(inc.getStatus())) {
            throw new BusinessConflictException("只有草稿状态的事件能开始调查，当前为「"
                    + statusLabel(inc.getStatus()) + "」");
        }
        if (!canEditContent(user, inc)) {
            throw new ForbiddenException("只有事件报告人、关联值守人员或安全主管能开始调查");
        }
        if (!user.id().equals(inc.getReporterId()) && !user.isSafetyOfficer()) {
            throw new ForbiddenException("草稿阶段只允许报告人（或安全主管）开始调查");
        }
        checkVersion(inc, action.getExpectedVersion(), null);
        String before = inc.getStatus();
        inc.setStatus(Incident.STATUS_INVESTIGATING);
        appendRevision(inc, IncidentRevision.TYPE_EDIT,
                trimToNull(action.getReason()) != null ? trimToNull(action.getReason()) : "开始调查，后续修订全部留痕",
                before, Incident.STATUS_INVESTIGATING, user);
        return detail(id);
    }

    /** 调查中/重新开启 → 待封存：必须四项结论齐备 */
    @Transactional
    public IncidentDetailDTO submitForSeal(Long id, IncidentStatusActionDTO action) {
        CurrentUser user = currentUserResolver.require();
        requireExpectedVersion(action.getExpectedVersion());
        Incident inc = lockOr404(id);
        checkViewPermission(user, inc);
        if (!Incident.STATUS_INVESTIGATING.equals(inc.getStatus())
                && !Incident.STATUS_REOPENED.equals(inc.getStatus())) {
            throw new BusinessConflictException("只有调查中或重新开启的事件能提交封存，当前为「"
                    + statusLabel(inc.getStatus()) + "」");
        }
        if (!canEditContent(user, inc)) {
            throw new ForbiddenException("只有事件报告人、关联值守人员或安全主管能提交封存");
        }
        checkVersion(inc, action.getExpectedVersion(), null);
        if (!inc.hasSealRequiredFields()) {
            throw new BusinessConflictException("提交封存被拒绝：原因结论、纠正措施、整改负责人、整改期限四项必须全部填写");
        }
        String before = inc.getStatus();
        inc.setStatus(Incident.STATUS_PENDING_SEAL);
        appendRevision(inc, IncidentRevision.TYPE_EDIT, "调查完成，提交安全主管封存",
                before, Incident.STATUS_PENDING_SEAL, user);
        return detail(id);
    }

    /** 待封存 → 调查中（主管退回补充） */
    @Transactional
    public IncidentDetailDTO backToInvestigating(Long id, IncidentStatusActionDTO action) {
        CurrentUser user = currentUserResolver.require();
        requireExpectedVersion(action.getExpectedVersion());
        Incident inc = lockOr404(id);
        checkViewPermission(user, inc);
        if (!Incident.STATUS_PENDING_SEAL.equals(inc.getStatus())) {
            throw new BusinessConflictException("只有待封存状态能退回调查，当前为「" + statusLabel(inc.getStatus()) + "」");
        }
        if (!user.isSafetyOfficer() && !canEditContent(user, inc)) {
            throw new ForbiddenException("退回调查需要安全主管或事件参与人员身份");
        }
        if (action.getReason() == null || action.getReason().isBlank()) {
            throw new IllegalArgumentException("退回调查必须填写退回原因");
        }
        checkVersion(inc, action.getExpectedVersion(), null);
        String before = inc.getStatus();
        inc.setStatus(Incident.STATUS_INVESTIGATING);
        appendRevision(inc, IncidentRevision.TYPE_EDIT, "退回调查：" + action.getReason().trim(),
                before, Incident.STATUS_INVESTIGATING, user);
        return detail(id);
    }

    /**
     * 待封存 → 已封存。仅安全主管；四项结论齐备才放行。
     * 状态变更 + SEAL 修订同一事务，不可能封存成功而修订缺失。
     * 重新开启后的再次封存走同一方法，sealedCount 累加到 2，修订流水中两次封存之间的动作完整可见。
     */
    @Transactional
    public IncidentDetailDTO seal(Long id, IncidentStatusActionDTO action) {
        CurrentUser user = currentUserResolver.require();
        requireExpectedVersion(action.getExpectedVersion());
        Incident inc = lockOr404(id);
        checkViewPermission(user, inc);
        if (!user.isSafetyOfficer()) {
            throw new ForbiddenException(String.format(
                    "封存被拒绝：仅安全主管可封存异常事件，当前操作人「%s」是普通值班员", user.name()));
        }
        if (!Incident.STATUS_PENDING_SEAL.equals(inc.getStatus())) {
            throw new BusinessConflictException("只有待封存状态的事件能封存，当前为「"
                    + statusLabel(inc.getStatus()) + "」；如需封存请先提交封存");
        }
        checkVersion(inc, action.getExpectedVersion(), null);
        if (!inc.hasSealRequiredFields()) {
            throw new BusinessConflictException("封存被拒绝：原因结论、纠正措施、整改负责人、整改期限四项必须全部填写");
        }

        String before = inc.getStatus();
        LocalDateTime now = LocalDateTime.now();
        int sealNo = inc.getSealedCount() + 1;
        inc.setStatus(Incident.STATUS_SEALED);
        inc.setSealedCount(sealNo);
        inc.setLastSealTime(now);
        if (inc.getFirstSealTime() == null) {
            inc.setFirstSealTime(now);
        }
        inc.setSealedById(user.id());
        inc.setSealedByName(user.name());

        String reason = sealNo == 1 ? "第 1 次封存" : "第 " + sealNo + " 次封存（重新开启后再次封存）";
        appendRevision(inc, IncidentRevision.TYPE_SEAL, reason, before, Incident.STATUS_SEALED, user);
        log.warn("事件{}由{}完成第{}次封存", inc.getIncidentNo(), user.name(), sealNo);
        return detail(id);
    }

    /** 已封存 → 重新开启：仅安全主管，必须写清依据；再次封存时两次封存之间的修订即为期间发生的事 */
    @Transactional
    public IncidentDetailDTO reopen(Long id, IncidentStatusActionDTO action) {
        CurrentUser user = currentUserResolver.require();
        requireExpectedVersion(action.getExpectedVersion());
        Incident inc = lockOr404(id);
        checkViewPermission(user, inc);
        if (!user.isSafetyOfficer()) {
            throw new ForbiddenException(String.format(
                    "重新开启被拒绝：仅安全主管可重新开启已封存事件，当前操作人「%s」是普通值班员", user.name()));
        }
        if (!inc.isSealed()) {
            throw new BusinessConflictException("只有已封存的事件能重新开启，当前为「" + statusLabel(inc.getStatus()) + "」");
        }
        if (action.getReason() == null || action.getReason().isBlank()) {
            throw new IllegalArgumentException("重新开启必须写清依据（新证据/新事实/纠错原因）");
        }
        checkVersion(inc, action.getExpectedVersion(), null);
        String before = inc.getStatus();
        inc.setStatus(Incident.STATUS_REOPENED);
        appendRevision(inc, IncidentRevision.TYPE_REOPEN, "重新开启依据：" + action.getReason().trim(),
                before, Incident.STATUS_REOPENED, user);
        log.warn("事件{}由{}重新开启，依据：{}", inc.getIncidentNo(), user.name(), action.getReason());
        return detail(id);
    }

    /* ==================== 查询（行级权限） ==================== */

    @Transactional(readOnly = true)
    public List<IncidentListItemDTO> list() {
        CurrentUser user = currentUserResolver.require();
        List<Incident> list = user.isSafetyOfficer()
                ? incidentRepository.findAllByOrderByIdDesc()
                : incidentRepository.findVisibleForStaff(user.id());
        return list.stream().map(inc -> {
            FlightRoute route = routeRepository.findById(inc.getRouteId()).orElse(null);
            String flightDate = null;
            if (inc.getWatchId() != null) {
                flightDate = watchRepository.findById(inc.getWatchId())
                        .map(w -> w.getFlightDate().format(D)).orElse(null);
            }
            return IncidentListItemDTO.builder()
                    .id(inc.getId())
                    .incidentNo(inc.getIncidentNo())
                    .title(inc.getTitle())
                    .routeCode(route != null ? route.getRouteCode() : null)
                    .routeName(route != null ? route.getRouteName() : null)
                    .watchFlightDate(flightDate)
                    .foundTime(inc.getFoundTime())
                    .severity(inc.getSeverity())
                    .severityLabel(severityLabel(inc.getSeverity()))
                    .status(inc.getStatus())
                    .statusLabel(statusLabel(inc.getStatus()))
                    .version(inc.getVersion())
                    .sealedCount(inc.getSealedCount())
                    .reporterName(inc.getReporterName())
                    .ownerName(inc.getOwnerName())
                    .lastSealTime(inc.getLastSealTime())
                    .createTime(inc.getCreateTime())
                    .build();
        }).toList();
    }

    @Transactional(readOnly = true)
    public IncidentDetailDTO detail(Long id) {
        CurrentUser user = currentUserResolver.require();
        Incident inc = incidentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("异常事件不存在: " + id));
        checkViewPermission(user, inc);
        return toDetail(inc, user);
    }

    @Transactional(readOnly = true)
    public List<IncidentRevisionDTO> revisions(Long id) {
        CurrentUser user = currentUserResolver.require();
        Incident inc = incidentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("异常事件不存在: " + id));
        checkViewPermission(user, inc);
        List<IncidentRevision> all = revisionRepository.findByIncidentIdOrderByRevisionNoAsc(id);
        List<IncidentRevisionDTO> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            IncidentRevision r = all.get(i);
            List<String> changed = i == 0
                    ? List.of("初始建立")
                    : diffFields(all.get(i - 1), r);
            out.add(toRevisionDTO(r, changed));
        }
        return out;
    }

    /* ==================== 内部实现 ==================== */

    /** 追加深修订并推进版本；状态变更/正文修订共用，保证同生共死 */
    private void appendRevision(Incident inc, String type, String reason,
                                String statusBefore, String statusAfter, CurrentUser user) {
        int nextNo = inc.getVersion() + 1;
        IncidentRevision rev = baseRevisionBuilder(inc)
                .revisionNo(nextNo)
                .changeType(type)
                .changeReason(reason)
                .statusBefore(statusBefore)
                .statusAfter(statusAfter)
                .operatorId(user.id())
                .operatorName(user.name())
                .build();
        revisionRepository.save(rev);
        inc.setVersion(nextNo);
        inc.setCurrentRevisionId(rev.getId());
        incidentRepository.save(inc);
    }

    private IncidentRevision.IncidentRevisionBuilder baseRevisionBuilder(Incident inc) {
        return IncidentRevision.builder()
                .incidentId(inc.getId())
                .title(inc.getTitle())
                .foundTime(inc.getFoundTime())
                .severity(inc.getSeverity())
                .incidentNote(inc.getIncidentNote())
                .handlingAction(inc.getHandlingAction())
                .evidenceDesc(inc.getEvidenceDesc())
                .rootCause(inc.getRootCause())
                .correctiveAction(inc.getCorrectiveAction())
                .ownerId(inc.getOwnerId())
                .ownerName(inc.getOwnerName())
                .dueDate(inc.getDueDate());
    }

    private void applyEditableFields(Incident inc, IncidentEditDTO dto, String ownerName) {
        inc.setTitle(dto.getTitle().trim());
        inc.setFoundTime(dto.getFoundTime());
        inc.setSeverity(dto.getSeverity());
        inc.setIncidentNote(dto.getIncidentNote().trim());
        inc.setHandlingAction(trimToNull(dto.getHandlingAction()));
        // 封存后更正接口已经拒绝 evidenceDesc 变化；草稿/调查中可继续补充证据
        inc.setEvidenceDesc(trimToNull(dto.getEvidenceDesc()));
        inc.setRootCause(trimToNull(dto.getRootCause()));
        inc.setCorrectiveAction(trimToNull(dto.getCorrectiveAction()));
        inc.setOwnerId(dto.getOwnerId() != null ? dto.getOwnerId() : null);
        inc.setOwnerName(ownerName);
        inc.setDueDate(dto.getDueDate());
    }

    private void validateCreate(IncidentCreateDTO dto) {
        if (dto == null) throw new IllegalArgumentException("请求体为空");
        if (dto.getRouteId() == null) throw new IllegalArgumentException("必须关联事发航线");
        if (dto.getTitle() == null || dto.getTitle().isBlank()) throw new IllegalArgumentException("必须填写事件标题");
        if (dto.getFoundTime() == null) throw new IllegalArgumentException("必须填写异常发现时间");
        if (dto.getSeverity() == null) throw new IllegalArgumentException("必须选择严重级别");
        if (!List.of(Incident.SEVERITY_MINOR, Incident.SEVERITY_MAJOR, Incident.SEVERITY_CRITICAL)
                .contains(dto.getSeverity())) {
            throw new IllegalArgumentException("严重级别非法：" + dto.getSeverity());
        }
        if (dto.getIncidentNote() == null || dto.getIncidentNote().isBlank()) {
            throw new IllegalArgumentException("必须填写现场经过");
        }
        if (dto.getFoundTime().isAfter(LocalDateTime.now().plusMinutes(1))) {
            throw new IllegalArgumentException("异常发现时间不能晚于当前时间（事件复盘只针对已发生的飞行）");
        }
    }

    private void validateEditPayload(IncidentEditDTO dto) {
        if (dto.getTitle() == null || dto.getTitle().isBlank()) throw new IllegalArgumentException("标题不能为空");
        if (dto.getFoundTime() == null) throw new IllegalArgumentException("发现时间不能为空");
        if (dto.getIncidentNote() == null || dto.getIncidentNote().isBlank()) {
            throw new IllegalArgumentException("现场经过不能为空");
        }
        if (!List.of(Incident.SEVERITY_MINOR, Incident.SEVERITY_MAJOR, Incident.SEVERITY_CRITICAL)
                .contains(dto.getSeverity())) {
            throw new IllegalArgumentException("严重级别非法：" + dto.getSeverity());
        }
        if (dto.getOwnerId() != null && dto.getDueDate() == null) {
            throw new IllegalArgumentException("填写了整改负责人就必须填写整改期限");
        }
        if (dto.getDueDate() != null && dto.getOwnerId() == null) {
            throw new IllegalArgumentException("填写了整改期限就必须指定整改负责人");
        }
    }

    /* ---------- 版本口径 ---------- */

    private void requireExpectedVersion(Integer v) {
        if (v == null) {
            throw new IllegalArgumentException("必须携带 expectedVersion（你编辑所依据的版本号），整份事件版本冲突口径不允许无版本保存");
        }
    }

    private Incident lockOr404(Long id) {
        return incidentRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("异常事件不存在: " + id));
    }

    /**
     * 版本冲突判定：expectedVersion != 当前 version 即整体拒绝。
     * 冲突响应带：冲突字段（你的值 vs 最新值 vs 最新改动人）、最新版本号与最新详情。
     * 注意：必须在加锁后调用，保证“比对 + 写入”期间没有第三个事务插入新版本。
     *
     * @param submitted 正文编辑时为本次提交 DTO（可逐字段比对）；状态流转时为 null（只报状态冲突）
     */
    private void checkVersion(Incident latest, int expectedVersion, IncidentEditDTO submitted) {
        if (latest.getVersion() == expectedVersion) {
            return;
        }
        IncidentDetailDTO latestDetail = toDetail(latest, currentUserResolver.require());

        List<IncidentConflictDTO.ConflictField> conflicts = new ArrayList<>();
        IncidentRevision lastRev = revisionRepository
                .findByIncidentIdAndRevisionNo(latest.getId(), latest.getVersion()).orElse(null);
        Map<String, String[]> submittedVsLatest = submitted != null
                ? collectSubmittedVsLatest(submitted, latest) : Map.of();
        for (Map.Entry<String, String[]> e : submittedVsLatest.entrySet()) {
            conflicts.add(IncidentConflictDTO.ConflictField.builder()
                    .field(e.getKey())
                    .fieldLabel(FIELD_LABELS.get(e.getKey()))
                    .yourValue(e.getValue()[0])
                    .latestValue(e.getValue()[1])
                    .latestChangedBy(lastRev != null ? lastRev.getOperatorName() + " @ " + fmt(lastRev.getCreateTime()) : null)
                    .latestChangeType(lastRev != null ? revisionTypeLabel(lastRev.getChangeType()) : null)
                    .build());
        }
        // 非编辑类操作（封存/重开/状态流转）冲突，或两边改的字段恰好不相交：提示状态/版本已被推进
        if (conflicts.isEmpty()) {
            conflicts.add(IncidentConflictDTO.ConflictField.builder()
                    .field("status")
                    .fieldLabel("事件状态/版本")
                    .yourValue("你依据的版本 " + expectedVersion)
                    .latestValue(statusLabel(latest.getStatus()) + "（版本 " + latest.getVersion() + "）")
                    .latestChangedBy(lastRev != null ? lastRev.getOperatorName() + " @ " + fmt(lastRev.getCreateTime()) : null)
                    .latestChangeType(lastRev != null ? revisionTypeLabel(lastRev.getChangeType()) : null)
                    .build());
        }

        throw new IncidentVersionConflictException(IncidentConflictDTO.builder()
                .incidentId(latest.getId())
                .incidentNo(latest.getIncidentNo())
                .expectedVersion(expectedVersion)
                .latestVersion(latest.getVersion())
                .message(String.format(
                        "版本冲突：你打开的是第 %d 版，但事件已被他人更新到第 %d 版。为避免静默覆盖，本次保存未生效；"
                      + "请查看下列冲突字段，放弃修改或基于最新内容重新编辑。", expectedVersion, latest.getVersion()))
                .conflictFields(conflicts)
                .latest(latestDetail)
                .build());
    }

    /** 返回 field -> [你的值, 最新值]，仅包含双方不同的字段 */
    private Map<String, String[]> collectSubmittedVsLatest(IncidentEditDTO dto, Incident latest) {
        Map<String, String[]> map = new LinkedHashMap<>();
        addConflict(map, "title", dto.getTitle(), latest.getTitle());
        addConflict(map, "foundTime", fmt(dto.getFoundTime()), fmt(latest.getFoundTime()));
        addConflict(map, "severity", severityLabelSafe(dto.getSeverity()), severityLabel(latest.getSeverity()));
        addConflict(map, "incidentNote", dto.getIncidentNote(), latest.getIncidentNote());
        addConflict(map, "handlingAction", dto.getHandlingAction(), latest.getHandlingAction());
        addConflict(map, "evidenceDesc", dto.getEvidenceDesc(), latest.getEvidenceDesc());
        addConflict(map, "rootCause", dto.getRootCause(), latest.getRootCause());
        addConflict(map, "correctiveAction", dto.getCorrectiveAction(), latest.getCorrectiveAction());
        addConflict(map, "ownerId",
                dto.getOwnerId() == null ? "未指定" : staffName(dto.getOwnerId()),
                latest.getOwnerId() == null ? "未指定" : latest.getOwnerName());
        addConflict(map, "dueDate", fmtDate(dto.getDueDate()), fmtDate(latest.getDueDate()));
        return map;
    }

    private void addConflict(Map<String, String[]> map, String field, String your, String latestV) {
        String y = your == null ? "" : your.trim();
        String l = latestV == null ? "" : latestV.trim();
        if (!Objects.equals(y, l)) {
            map.put(field, new String[]{your == null ? "(空)" : your, latestV == null ? "(空)" : latestV});
        }
    }

    /* ---------- 权限 ---------- */

    /** 普通值班员只能查看自己报告/负责/参与值守的事件；安全主管全部可见。直接请求后端同样适用。 */
    private void checkViewPermission(CurrentUser user, Incident inc) {
        if (user.isSafetyOfficer()) {
            return;
        }
        if (user.id().equals(inc.getReporterId()) || user.id().equals(inc.getOwnerId())) {
            return;
        }
        if (inc.getWatchId() != null) {
            Optional<FlightWatch> w = watchRepository.findById(inc.getWatchId());
            if (w.isPresent() && (user.id().equals(w.get().getOperatorId())
                    || user.id().equals(w.get().getReviewerId()))) {
                return;
            }
        }
        throw new ForbiddenException(String.format(
                "无权查看事件「%s」：普通值班员只能查看自己报告、负责或作为操作员/复核员参与的事件",
                inc.getIncidentNo()));
    }

    /** 正文编辑资格：报告人、关联值守操作员/复核员、安全主管 */
    private boolean canEditContent(CurrentUser user, Incident inc) {
        if (user.isSafetyOfficer() || user.id().equals(inc.getReporterId())) {
            return true;
        }
        if (inc.getWatchId() != null) {
            return watchRepository.findById(inc.getWatchId())
                    .map(w -> user.id().equals(w.getOperatorId()) || user.id().equals(w.getReviewerId()))
                    .orElse(false);
        }
        return false;
    }

    /* ---------- 视图组装 ---------- */

    private IncidentDetailDTO toDetail(Incident inc, CurrentUser user) {
        FlightRoute route = routeRepository.findById(inc.getRouteId()).orElse(null);
        FlightWatch watch = inc.getWatchId() != null
                ? watchRepository.findById(inc.getWatchId()).orElse(null) : null;

        List<IncidentAnchor> snapAnchors = incidentAnchorRepository.findByIncidentIdOrderByIdAsc(inc.getId());
        List<IncidentAnchorViewDTO> anchorViews = snapAnchors.stream().map(sa -> {
            Anchor cur = anchorRepository.findById(sa.getAnchorId()).orElse(null);
            if (cur == null) {
                return IncidentAnchorViewDTO.builder()
                        .anchorId(sa.getAnchorId()).anchorCode(sa.getAnchorCode())
                        .locationDesc(sa.getLocationDesc()).anchorZone(sa.getAnchorZone())
                        .statusSnapshot(sa.getStatusSnapshot()).maxWeight(sa.getMaxWeight())
                        .currentExists(false).changed(true).build();
            }
            String curStatus = cur.getStatus() != null && cur.getStatus() == 1 ? "启用" : "停用";
            boolean changed = !Objects.equals(cur.getLocationDesc(), sa.getLocationDesc())
                    || !Objects.equals(cur.getAnchorZone(), sa.getAnchorZone())
                    || !Objects.equals(curStatus, sa.getStatusSnapshot())
                    || (cur.getMaxWeight() != null && sa.getMaxWeight() != null
                        && cur.getMaxWeight().compareTo(sa.getMaxWeight()) != 0);
            return IncidentAnchorViewDTO.builder()
                    .anchorId(cur.getId()).anchorCode(sa.getAnchorCode())
                    .locationDesc(sa.getLocationDesc()).anchorZone(sa.getAnchorZone())
                    .statusSnapshot(sa.getStatusSnapshot()).maxWeight(sa.getMaxWeight())
                    .currentExists(true)
                    .currentLocationDesc(cur.getLocationDesc())
                    .currentAnchorZone(cur.getAnchorZone())
                    .currentStatus(curStatus)
                    .currentMaxWeight(cur.getMaxWeight())
                    .changed(changed).build();
        }).toList();

        // 航线/人员差异：snapshot* 为建事件时冻结的列，route/watch 为当前资料
        SnapshotStore.Snapshot snap = snapshotStore.read(inc, route, watch);

        boolean routeChanged = false;
        if (route != null) {
            routeChanged = !Objects.equals(route.getRouteName(), snap.routeName())
                    || !Objects.equals(route.getWindLevel(), snap.routeWindLevel())
                    || !Objects.equals(route.getRouteCode(), snap.routeCode());
        }
        boolean staffChanged = false;
        String curOpName = null;
        String curRevName = null;
        String curWatchStatus = null;
        boolean watchExists = watch != null;
        if (watch != null) {
            curWatchStatus = statusOfWatch(watch.getStatus());
            curOpName = nameOf(watch.getOperatorId(), watch.getOperatorName());
            curRevName = nameOf(watch.getReviewerId(), watch.getReviewerName());
            staffChanged = !Objects.equals(curOpName, snap.operatorName())
                    || !Objects.equals(curRevName, snap.reviewerName());
        }

        boolean related = canEditContent(user, inc);
        boolean isReporter = user.id().equals(inc.getReporterId());
        boolean sealReady = inc.hasSealRequiredFields();
        String sealBlockReason = sealReady ? null
                : "原因结论、纠正措施、整改负责人、整改期限四项未全部填写，安全主管无法封存";

        // 正文编辑口径：草稿仅报告人/主管；调查中/重新开启为参与人员；待封存与已封存不走普通编辑
        // （已封存只走 canCorrect 的“带理由更正”）
        boolean canEditNow = switch (inc.getStatus()) {
            case Incident.STATUS_DRAFT -> related && (isReporter || user.isSafetyOfficer());
            case Incident.STATUS_INVESTIGATING, Incident.STATUS_REOPENED -> related;
            default -> false;
        };

        return IncidentDetailDTO.builder()
                .id(inc.getId())
                .incidentNo(inc.getIncidentNo())
                .title(inc.getTitle())
                .watchId(inc.getWatchId())
                .routeId(inc.getRouteId())
                .foundTime(inc.getFoundTime())
                .severity(inc.getSeverity())
                .severityLabel(severityLabel(inc.getSeverity()))
                .incidentNote(inc.getIncidentNote())
                .handlingAction(inc.getHandlingAction())
                .evidenceDesc(inc.getEvidenceDesc())
                .rootCause(inc.getRootCause())
                .correctiveAction(inc.getCorrectiveAction())
                .ownerId(inc.getOwnerId())
                .ownerName(inc.getOwnerName())
                .dueDate(inc.getDueDate())
                .reporterId(inc.getReporterId())
                .reporterName(inc.getReporterName())
                .status(inc.getStatus())
                .statusLabel(statusLabel(inc.getStatus()))
                .version(inc.getVersion())
                .sealedCount(inc.getSealedCount())
                .firstSealTime(inc.getFirstSealTime())
                .lastSealTime(inc.getLastSealTime())
                .sealedByName(inc.getSealedByName())
                .createTime(inc.getCreateTime())
                .snapshotRouteCode(snap.routeCode())
                .snapshotRouteName(snap.routeName())
                .snapshotRouteWindLevel(snap.routeWindLevel())
                .snapshotRouteGroup(snap.routeGroup())
                .snapshotWatchFlightDate(snap.flightDate())
                .snapshotWatchTakeoff(snap.takeoff())
                .snapshotOperatorName(snap.operatorName())
                .snapshotReviewerName(snap.reviewerName())
                .anchors(anchorViews)
                .currentRouteExists(route != null)
                .currentRouteCode(route != null ? route.getRouteCode() : null)
                .currentRouteName(route != null ? route.getRouteName() : null)
                .currentRouteWindLevel(route != null ? route.getWindLevel() : null)
                .currentRouteStatus(route != null ? (route.getStatus() != null && route.getStatus() == 1 ? "启用" : "停用") : null)
                .routeChanged(route == null || routeChanged)
                .currentWatchExists(watchExists)
                .currentWatchStatus(curWatchStatus)
                .currentOperatorName(curOpName)
                .currentReviewerName(curRevName)
                .staffChanged(staffChanged)
                .canEdit(canEditNow)
                .canStartInvestigation(canEditNow)
                .canSubmitSeal(related && (Incident.STATUS_INVESTIGATING.equals(inc.getStatus())
                        || Incident.STATUS_REOPENED.equals(inc.getStatus())))
                .canBackToInvestigating((user.isSafetyOfficer() || related)
                        && Incident.STATUS_PENDING_SEAL.equals(inc.getStatus()))
                .canSeal(user.isSafetyOfficer() && Incident.STATUS_PENDING_SEAL.equals(inc.getStatus()))
                .canReopen(user.isSafetyOfficer() && inc.isSealed())
                .canCorrect((user.isSafetyOfficer() || user.id().equals(inc.getReporterId())) && inc.isSealed())
                .sealBlockReason(sealBlockReason)
                .concurrencyPolicy(CONCURRENCY_POLICY)
                .fieldMergeRejectedReason(FIELD_MERGE_REJECTED_REASON)
                .build();
    }

    private IncidentRevisionDTO toRevisionDTO(IncidentRevision r, List<String> changedFields) {
        return IncidentRevisionDTO.builder()
                .id(r.getId())
                .revisionNo(r.getRevisionNo())
                .changeType(r.getChangeType())
                .changeTypeLabel(revisionTypeLabel(r.getChangeType()))
                .changeReason(r.getChangeReason())
                .title(r.getTitle())
                .foundTime(r.getFoundTime())
                .severity(r.getSeverity())
                .severityLabel(severityLabel(r.getSeverity()))
                .incidentNote(r.getIncidentNote())
                .handlingAction(r.getHandlingAction())
                .evidenceDesc(r.getEvidenceDesc())
                .rootCause(r.getRootCause())
                .correctiveAction(r.getCorrectiveAction())
                .ownerId(r.getOwnerId())
                .ownerName(r.getOwnerName())
                .dueDate(r.getDueDate())
                .statusBefore(r.getStatusBefore())
                .statusBeforeLabel(r.getStatusBefore() == null ? null : statusLabel(r.getStatusBefore()))
                .statusAfter(r.getStatusAfter())
                .statusAfterLabel(r.getStatusAfter() == null ? null : statusLabel(r.getStatusAfter()))
                .operatorId(r.getOperatorId())
                .operatorName(r.getOperatorName())
                .createTime(r.getCreateTime())
                .changedFields(changedFields)
                .build();
    }

    private List<String> diffFields(IncidentRevision prev, IncidentRevision cur) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(prev.getTitle(), cur.getTitle())) changed.add(FIELD_LABELS.get("title"));
        if (!Objects.equals(prev.getFoundTime(), cur.getFoundTime())) changed.add(FIELD_LABELS.get("foundTime"));
        if (!Objects.equals(prev.getSeverity(), cur.getSeverity())) changed.add(FIELD_LABELS.get("severity"));
        if (!Objects.equals(prev.getIncidentNote(), cur.getIncidentNote())) changed.add(FIELD_LABELS.get("incidentNote"));
        if (!Objects.equals(prev.getHandlingAction(), cur.getHandlingAction())) changed.add(FIELD_LABELS.get("handlingAction"));
        if (!Objects.equals(prev.getEvidenceDesc(), cur.getEvidenceDesc())) changed.add(FIELD_LABELS.get("evidenceDesc"));
        if (!Objects.equals(prev.getRootCause(), cur.getRootCause())) changed.add(FIELD_LABELS.get("rootCause"));
        if (!Objects.equals(prev.getCorrectiveAction(), cur.getCorrectiveAction())) changed.add(FIELD_LABELS.get("correctiveAction"));
        if (!Objects.equals(prev.getOwnerId(), cur.getOwnerId())) changed.add(FIELD_LABELS.get("ownerId"));
        if (!Objects.equals(prev.getDueDate(), cur.getDueDate())) changed.add(FIELD_LABELS.get("dueDate"));
        if (!Objects.equals(prev.getStatusAfter(), cur.getStatusAfter())) changed.add("状态");
        return changed;
    }

    /* ---------- 事发快照存取（航线/值守关键名称、风级、人员） ---------- */

    /**
     * 航线/值守快照保存在 incident 表的 snapshot_* 冻结列（见 schema.sql），
     * 建立后任何代码路径都不再改写这些列。
     */
    private final SnapshotStore snapshotStore = new SnapshotStore();

    private class SnapshotStore {
        record Snapshot(String routeCode, String routeName, String routeWindLevel, String routeGroup,
                        String flightDate, String takeoff, String operatorName, String reviewerName) {
            static Snapshot empty() {
                return new Snapshot(null, null, null, null, null, null, null, null);
            }
        }

        Snapshot read(Incident inc, FlightRoute route, FlightWatch watch) {
            // 快照列优先（建事件后改名/改风级不影响显示）
            String code = inc.getSnapshotRouteCode();
            if (code != null) {
                return new Snapshot(code, inc.getSnapshotRouteName(), inc.getSnapshotRouteWindLevel(),
                        inc.getSnapshotRouteGroup(), inc.getSnapshotWatchFlightDate(), inc.getSnapshotWatchTakeoff(),
                        inc.getSnapshotOperatorName(), inc.getSnapshotReviewerName());
            }
            // 兼容：无冻结列时退回建事件时的现值
            if (route == null) return Snapshot.empty();
            return new Snapshot(route.getRouteCode(), route.getRouteName(), route.getWindLevel(), route.getRouteGroup(),
                    watch != null ? watch.getFlightDate().format(D) : null,
                    watch != null ? fmt(watch.getPlannedTakeoff()) : null,
                    watch != null ? watch.getOperatorName() : null,
                    watch != null ? watch.getReviewerName() : null);
        }

        void freeze(Incident inc, FlightRoute route, FlightWatch watch) {
            inc.setSnapshotRouteCode(route.getRouteCode());
            inc.setSnapshotRouteName(route.getRouteName());
            inc.setSnapshotRouteWindLevel(route.getWindLevel());
            inc.setSnapshotRouteGroup(route.getRouteGroup());
            if (watch != null) {
                inc.setSnapshotWatchFlightDate(watch.getFlightDate().format(D));
                inc.setSnapshotWatchTakeoff(fmt(watch.getPlannedTakeoff()));
                // 值守当前排班姓名即事发时姓名（之后人员改名由这里冻结的值兜住）
                inc.setSnapshotOperatorName(staffName(watch.getOperatorId()));
                inc.setSnapshotReviewerName(staffName(watch.getReviewerId()));
            }
        }
    }

    /* ---------- 杂项 ---------- */

    private String generateIncidentNo() {
        String prefix = "INC-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        long count = incidentRepository.countByIncidentNoStartingWith(prefix);
        for (int i = 0; i < 20; i++) {
            String no = prefix + String.format("%03d", count + 1 + i)
                    + String.format("%02d", (int) (Math.random() * 100));
            if (!incidentRepository.existsByIncidentNo(no)) {
                return no;
            }
        }
        return prefix + System.currentTimeMillis() % 1_000_000L;
    }

    private String staffName(Long id) {
        return id == null ? null : staffRepository.findById(id).map(GroundStaff::getStaffName).orElse(null);
    }

    private String nameOf(Long id, String fallback) {
        return staffRepository.findById(id).map(GroundStaff::getStaffName).orElse(fallback);
    }

    private static String statusOfWatch(String s) {
        return switch (s) {
            case FlightWatch.STATUS_DRAFT -> "草拟";
            case FlightWatch.STATUS_PENDING_REVIEW -> "待复核";
            case FlightWatch.STATUS_READY -> "就绪";
            case FlightWatch.STATUS_CANCELLED -> "取消";
            default -> s;
        };
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? null : t.format(DT);
    }

    private static String fmtDate(LocalDate d) {
        return d == null ? null : d.format(D);
    }

    private static String severityLabelSafe(String s) {
        return s == null ? null : severityLabel(s);
    }

    private static boolean equalsText(String a, String b) {
        return Objects.equals(trimToNull(a), trimToNull(b));
    }
}
