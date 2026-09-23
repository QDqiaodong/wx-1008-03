package com.px.base.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.px.base.dto.IncidentAnchorViewDTO;
import com.px.base.dto.IncidentConflictDTO;
import com.px.base.dto.IncidentContentDTO;
import com.px.base.dto.IncidentCreateDTO;
import com.px.base.dto.IncidentEditDTO;
import com.px.base.dto.IncidentListItemDTO;
import com.px.base.dto.IncidentRevisionViewDTO;
import com.px.base.dto.IncidentSealRoundViewDTO;
import com.px.base.dto.IncidentTransitionDTO;
import com.px.base.dto.IncidentViewDTO;
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
import com.px.base.security.CurrentUser;
import com.px.base.security.CurrentUserResolver;
import com.px.base.security.ForbiddenException;
import com.px.base.security.IncidentVersionConflictException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 飞行异常事件复盘核心服务。
 *
 * 三条不可绕过的主线：
 *  1) 快照冻结——建档时把航线名称/风级、值守两人姓名、锚点状态/承重、人员姓名复制进事件自有表，
 *     此后基础资料修改与事件展示完全解耦，同时保留 current* 实时值用于差异对比；
 *  2) 整份事件版本冲突——所有写操作在事务内先对事件行加悲观写锁串行化，再比对 expectedVersion，
 *     版本不符整体拒绝并返回冲突字段与最新版本，绝不部分落库；
 *  3) 不可变修订 + 状态同事务——每次正文修订/状态流转都向只追加的修订表插入一条（含前后整份正文），
 *     封存/重开同时写封存轮次，与事件状态更新在同一事务，同成同败。
 */
@Service
@Slf4j
public class IncidentService {

    private static final DateTimeFormatter CODE_FMT = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    /** 正文参与版本比对的字段：JSON key -> 中文展示名 */
    private static final LinkedHashMap<String, String> CONTENT_FIELDS = new LinkedHashMap<>();

    static {
        CONTENT_FIELDS.put("title", IncidentConflictDTO.FIELD_TITLE);
        CONTENT_FIELDS.put("severity", IncidentConflictDTO.FIELD_SEVERITY);
        CONTENT_FIELDS.put("foundTime", IncidentConflictDTO.FIELD_FOUND_TIME);
        CONTENT_FIELDS.put("sceneNarrative", IncidentConflictDTO.FIELD_SCENE);
        CONTENT_FIELDS.put("handlingActions", IncidentConflictDTO.FIELD_ACTIONS);
        CONTENT_FIELDS.put("evidenceNote", IncidentConflictDTO.FIELD_EVIDENCE);
        CONTENT_FIELDS.put("causeConclusion", IncidentConflictDTO.FIELD_CAUSE);
        CONTENT_FIELDS.put("correctiveAction", IncidentConflictDTO.FIELD_CORRECTIVE);
        CONTENT_FIELDS.put("ownerId", IncidentConflictDTO.FIELD_OWNER);
        CONTENT_FIELDS.put("dueDate", IncidentConflictDTO.FIELD_DUE);
    }

    private final IncidentEventRepository eventRepository;
    private final IncidentAnchorSnapshotRepository anchorSnapshotRepository;
    private final IncidentRevisionRepository revisionRepository;
    private final IncidentSealRoundRepository sealRoundRepository;
    private final FlightRouteRepository routeRepository;
    private final FlightWatchRepository watchRepository;
    private final AnchorRepository anchorRepository;
    private final GroundStaffRepository staffRepository;
    private final CurrentUserResolver currentUserResolver;
    private final ObjectMapper objectMapper;

    public IncidentService(IncidentEventRepository eventRepository,
                           IncidentAnchorSnapshotRepository anchorSnapshotRepository,
                           IncidentRevisionRepository revisionRepository,
                           IncidentSealRoundRepository sealRoundRepository,
                           FlightRouteRepository routeRepository,
                           FlightWatchRepository watchRepository,
                           AnchorRepository anchorRepository,
                           GroundStaffRepository staffRepository,
                           CurrentUserResolver currentUserResolver) {
        this.eventRepository = eventRepository;
        this.anchorSnapshotRepository = anchorSnapshotRepository;
        this.revisionRepository = revisionRepository;
        this.sealRoundRepository = sealRoundRepository;
        this.routeRepository = routeRepository;
        this.watchRepository = watchRepository;
        this.anchorRepository = anchorRepository;
        this.staffRepository = staffRepository;
        this.currentUserResolver = currentUserResolver;
        // 修订正文以 JSON 存储：日期输出 ISO 字符串（与前端 IncidentContent 一致），便于字段比对
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /* ============================== 建档 ============================== */

    @Transactional
    public IncidentViewDTO create(IncidentCreateDTO dto) {
        CurrentUser user = currentUserResolver.require();
        validateCreate(dto);

        FlightRoute route = routeRepository.findById(dto.getRouteId())
                .orElseThrow(() -> new IllegalArgumentException("关联航线不存在: " + dto.getRouteId()));

        FlightWatch watch = null;
        if (dto.getWatchId() != null) {
            watch = watchRepository.findById(dto.getWatchId())
                    .orElseThrow(() -> new IllegalArgumentException("关联值守安排不存在: " + dto.getWatchId()));
            if (!route.getId().equals(watch.getRouteId())) {
                throw new IllegalArgumentException(String.format(
                        "值守安排[id=%s]属于航线%s，与所选航线%s不一致，不能关联",
                        watch.getId(), watch.getRouteCode(), route.getRouteCode()));
            }
        }

        List<Anchor> anchors = new ArrayList<>();
        if (dto.getAnchorIds() != null) {
            for (Long anchorId : dto.getAnchorIds()) {
                Anchor anchor = anchorRepository.findById(anchorId)
                        .orElseThrow(() -> new IllegalArgumentException("涉及锚点不存在: " + anchorId));
                anchors.add(anchor);
            }
        }

        // 涉及人员 = 手动选择的人员 ∪ 值守操作员/复核员（按 ID 去重，保序）
        LinkedHashSet<Long> involvedIds = new LinkedHashSet<>();
        if (dto.getInvolvedStaffIds() != null) {
            for (Long sid : dto.getInvolvedStaffIds()) {
                GroundStaff s = staffRepository.findById(sid)
                        .orElseThrow(() -> new IllegalArgumentException("涉及人员不存在: " + sid));
                if (s.getStatus() == null || s.getStatus() != 1) {
                    throw new IllegalArgumentException("涉及人员「" + s.getStaffName() + "」已停用，不能加入事件");
                }
                involvedIds.add(s.getId());
            }
        }
        if (watch != null) {
            involvedIds.add(watch.getOperatorId());
            involvedIds.add(watch.getReviewerId());
        }
        List<GroundStaff> involvedStaff = involvedIds.stream()
                .map(id -> staffRepository.findById(id).orElseThrow()).toList();

        Long ownerId = dto.getOwnerId();
        GroundStaff owner = null;
        if (ownerId != null) {
            owner = staffRepository.findById(ownerId)
                    .orElseThrow(() -> new IllegalArgumentException("整改负责人不存在: " + ownerId));
        }

        LocalDateTime foundTime = parseDateTime(dto.getFoundTime(), "发现时间");
        LocalDate dueDate = dto.getDueDate() == null || dto.getDueDate().isBlank()
                ? null : parseDate(dto.getDueDate(), "整改期限");

        IncidentEvent event = IncidentEvent.builder()
                .incidentCode(generateCode())
                .title(dto.getTitle().trim())
                .status(IncidentEvent.STATUS_DRAFT)
                .version(0L)
                .severity(dto.getSeverity())
                .foundTime(foundTime)
                .sceneNarrative(trimToNull(dto.getSceneNarrative()))
                .handlingActions(trimToNull(dto.getHandlingActions()))
                .evidenceNote(trimToNull(dto.getEvidenceNote()))
                .causeConclusion(trimToNull(dto.getCauseConclusion()))
                .correctiveAction(trimToNull(dto.getCorrectiveAction()))
                .ownerId(owner == null ? null : owner.getId())
                .ownerName(owner == null ? null : owner.getStaffName())
                .dueDate(dueDate)
                .routeId(route.getId())
                .snapRouteCode(route.getRouteCode())
                .snapRouteName(route.getRouteName())
                .snapRouteWindLevel(route.getWindLevel())
                .snapRouteWindSpeed(route.getWindSpeed())
                .watchId(watch == null ? null : watch.getId())
                .snapWatchTakeoff(watch == null ? null : watch.getPlannedTakeoff())
                .snapWatchEnd(watch == null ? null : watch.getPlannedEnd())
                .snapWatchOperatorId(watch == null ? null : watch.getOperatorId())
                .snapWatchOperatorName(watch == null ? null : watch.getOperatorName())
                .snapWatchReviewerId(watch == null ? null : watch.getReviewerId())
                .snapWatchReviewerName(watch == null ? null : watch.getReviewerName())
                .reporterId(user.id())
                .reporterName(user.name())
                .involvedStaffIds(Csv.join(involvedIds.stream().map(String::valueOf).toList()))
                .involvedStaffNames(involvedStaff.stream().map(GroundStaff::getStaffName)
                        .collect(java.util.stream.Collectors.joining("、")))
                .currentSealRound(0)
                .build();

        // 编号极小概率撞车：唯一约束兜底，重试一次
        try {
            event = eventRepository.save(event);
        } catch (RuntimeException ex) {
            event.setIncidentCode(generateCode());
            event = eventRepository.save(event);
        }

        // 冻结涉及锚点快照（复制事发当时的编号/区域/状态/承重/风区，之后锚点怎么改都与此无关）
        int sort = 0;
        for (Anchor a : anchors) {
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
                    .sortNo(sort++)
                    .build());
        }

        // seq=0 建档修订：保存初始整份正文，修订历史从第一条起就完整
        appendRevision(event, IncidentRevision.TYPE_CREATE, null,
                IncidentEvent.STATUS_DRAFT, user, "建立事件", null, contentJson(event), 0);

        log.info("建立异常事件: code={} id={} 报告人={} 航线={} 锚点数={}",
                event.getIncidentCode(), event.getId(), user.name(), route.getRouteCode(), anchors.size());
        return getView(event.getId());
    }

    private void validateCreate(IncidentCreateDTO dto) {
        if (dto.getTitle() == null || dto.getTitle().isBlank()) {
            throw new IllegalArgumentException("事件标题必须填写");
        }
        if (dto.getSeverity() == null || !Set.of(
                IncidentEvent.SEVERITY_MINOR, IncidentEvent.SEVERITY_MAJOR, IncidentEvent.SEVERITY_CRITICAL)
                .contains(dto.getSeverity())) {
            throw new IllegalArgumentException("严重级别必须是 MINOR/MAJOR/CRITICAL 之一");
        }
        if (dto.getFoundTime() == null || dto.getFoundTime().isBlank()) {
            throw new IllegalArgumentException("发现时间必须填写");
        }
        if (dto.getRouteId() == null) {
            throw new IllegalArgumentException("必须关联一条真实航线");
        }
    }

    /* ============================== 正文修订 ============================== */

    /**
     * 正文修订。状态决定修订类型与必填项：
     *  - DRAFT / INVESTIGATING：EDIT（草稿允许报告人补充；调查中每次修订留痕）；
     *  - REOPENED：CORRECT 封存后更正，理由必填；
     *  - PENDING_SEAL：必须先退回调查；SEALED：拒绝任何直接覆盖。
     */
    @Transactional
    public IncidentViewDTO editContent(Long id, IncidentEditDTO dto) {
        CurrentUser user = currentUserResolver.require();
        if (dto.getExpectedVersion() == null) {
            throw new IllegalArgumentException("缺少版本号 expectedVersion：必须基于打开时看到的版本提交");
        }
        IncidentContentDTO c = dto.getContent();
        if (c == null) {
            throw new IllegalArgumentException("缺少修订内容");
        }
        validateContent(c);

        IncidentEvent event = lockAndCheckVisible(id, user);
        // 版本口径先于状态判定：后到者拿着旧版本，无论当前是什么状态都必须先看见冲突
        checkVersion(event, dto.getExpectedVersion(), c);

        String status = event.getStatus();
        if (IncidentEvent.STATUS_SEALED.equals(status)) {
            throw new com.px.base.security.BusinessConflictException(
                    "事件已封存，正文与证据不可直接覆盖；如需更正须由安全主管重新开启后，以带理由的更正修订提交");
        }
        if (IncidentEvent.STATUS_PENDING_SEAL.equals(status)) {
            throw new com.px.base.security.BusinessConflictException(
                    "事件已提交待封存，不能改正文；请先退回“调查中”再修订，修订后重新提交封存");
        }
        requireParticipant(user, event, "修订事件正文");

        boolean reopened = IncidentEvent.STATUS_REOPENED.equals(status);
        if (reopened && (dto.getReason() == null || dto.getReason().isBlank())) {
            throw new IllegalArgumentException("事件已重新开启，属于封存后更正，必须填写更正理由");
        }
        String type = reopened ? IncidentRevision.TYPE_CORRECT : IncidentRevision.TYPE_EDIT;

        String beforeJson = contentJson(event);
        applyContent(event, c);
        event.setVersion(event.getVersion() + 1);
        event = eventRepository.save(event);
        appendRevision(event, type, status, status, user,
                trimToNull(dto.getReason()), beforeJson, contentJson(event), event.getCurrentSealRound());

        log.info("事件{}正文修订({}) by={} version={}", event.getIncidentCode(), type, user.name(), event.getVersion());
        return getView(event.getId());
    }

    private void validateContent(IncidentContentDTO c) {
        if (c.getTitle() == null || c.getTitle().isBlank()) {
            throw new IllegalArgumentException("事件标题必须填写");
        }
        if (!Set.of(IncidentEvent.SEVERITY_MINOR, IncidentEvent.SEVERITY_MAJOR,
                IncidentEvent.SEVERITY_CRITICAL).contains(c.getSeverity())) {
            throw new IllegalArgumentException("严重级别必须是 MINOR/MAJOR/CRITICAL 之一");
        }
        if (c.getFoundTime() == null || c.getFoundTime().isBlank()) {
            throw new IllegalArgumentException("发现时间必须填写");
        }
        parseDateTime(c.getFoundTime(), "发现时间");
        if (c.getDueDate() != null) {
            parseDate(c.getDueDate().toString(), "整改期限");
        }
    }

    /** 草稿 → 调查中（报告人/参与人） */
    @Transactional
    public IncidentViewDTO enterInvestigating(Long id, IncidentTransitionDTO dto) {
        CurrentUser user = currentUserResolver.require();
        IncidentEvent event = lockAndCheckVisible(id, user);
        requireParticipant(user, event, "进入调查");
        checkVersion(event, dto);
        if (!IncidentEvent.STATUS_DRAFT.equals(event.getStatus())) {
            throw new com.px.base.security.BusinessConflictException(String.format(
                    "当前状态为「%s」，只有草稿能进入调查", IncidentLabels.status(event.getStatus())));
        }
        return transition(event, IncidentEvent.STATUS_INVESTIGATING,
                IncidentRevision.TYPE_ENTER_INVESTIGATING, user, trimToNull(dto.getBasis()));
    }

    /** 调查中/重新开启 → 待封存（报告人/参与人；四要素必填） */
    @Transactional
    public IncidentViewDTO requestSeal(Long id, IncidentTransitionDTO dto) {
        CurrentUser user = currentUserResolver.require();
        IncidentEvent event = lockAndCheckVisible(id, user);
        requireParticipant(user, event, "提交待封存");
        checkVersion(event, dto);
        // 首次封存前必须在调查中；重新开启后完成更正也可直接再次提交待封存
        if (!IncidentEvent.STATUS_INVESTIGATING.equals(event.getStatus())
                && !IncidentEvent.STATUS_REOPENED.equals(event.getStatus())) {
            throw new com.px.base.security.BusinessConflictException(String.format(
                    "当前状态为「%s」，只有调查中或重新开启的事件能提交待封存",
                    IncidentLabels.status(event.getStatus())));
        }
        requireSealElements(event, "提交待封存");
        return transition(event, IncidentEvent.STATUS_PENDING_SEAL,
                IncidentRevision.TYPE_REQUEST_SEAL, user, trimToNull(dto.getBasis()));
    }

    /** 待封存 → 调查中（发现还要补查；报告人/参与人） */
    @Transactional
    public IncidentViewDTO backToInvestigating(Long id, IncidentTransitionDTO dto) {
        CurrentUser user = currentUserResolver.require();
        IncidentEvent event = lockAndCheckVisible(id, user);
        requireParticipant(user, event, "退回调查");
        checkVersion(event, dto);
        if (!IncidentEvent.STATUS_PENDING_SEAL.equals(event.getStatus())) {
            throw new com.px.base.security.BusinessConflictException(String.format(
                    "当前状态为「%s」，只有待封存事件能退回调查", IncidentLabels.status(event.getStatus())));
        }
        return transition(event, IncidentEvent.STATUS_INVESTIGATING,
                IncidentRevision.TYPE_ENTER_INVESTIGATING, user, trimToNull(dto.getBasis()));
    }

    /** 待封存 → 已封存（仅安全主管；封存轮次落库，与修订同事务） */
    @Transactional
    public IncidentViewDTO seal(Long id, IncidentTransitionDTO dto) {
        CurrentUser user = currentUserResolver.require();
        IncidentEvent event = lockAndCheckVisible(id, user);
        if (!user.isSafetyOfficer()) {
            throw new ForbiddenException(String.format(
                    "封存被拒绝：仅安全主管可封存异常事件，当前操作人「%s」是普通值班员", user.name()));
        }
        checkVersion(event, dto);
        if (!IncidentEvent.STATUS_PENDING_SEAL.equals(event.getStatus())) {
            throw new com.px.base.security.BusinessConflictException(String.format(
                    "当前状态为「%s」，只有待封存事件能封存", IncidentLabels.status(event.getStatus())));
        }
        requireSealElements(event, "封存");

        String beforeJson = contentJson(event);
        String fromStatus = event.getStatus();
        // 轮次号 = 历次封存最大轮次 + 1（每轮封存一行；重开只回填该行不新增，再次封存产生新行）
        int roundNo = sealRoundRepository.findByIncidentIdOrderByRoundNoAsc(event.getId()).stream()
                .map(IncidentSealRound::getRoundNo).max(Integer::compare).orElse(0) + 1;

        LocalDateTime now = LocalDateTime.now();
        event.setStatus(IncidentEvent.STATUS_SEALED);
        event.setVersion(event.getVersion() + 1);
        event.setCurrentSealRound(roundNo);
        event = eventRepository.save(event);

        sealRoundRepository.save(IncidentSealRound.builder()
                .incidentId(event.getId())
                .roundNo(roundNo)
                .sealTime(now)
                .sealedById(user.id())
                .sealedByName(user.name())
                .sealedVersion(event.getVersion())
                .build());
        appendRevision(event, IncidentRevision.TYPE_SEAL, fromStatus,
                IncidentEvent.STATUS_SEALED, user, trimToNull(dto.getBasis()),
                beforeJson, contentJson(event), roundNo);

        log.warn("事件{}第{}轮封存 by={} version={}", event.getIncidentCode(), roundNo, user.name(), event.getVersion());
        return getView(event.getId());
    }

    /** 已封存 → 重新开启（仅安全主管；依据必填；回填当前封存轮次） */
    @Transactional
    public IncidentViewDTO reopen(Long id, IncidentTransitionDTO dto) {
        CurrentUser user = currentUserResolver.require();
        IncidentEvent event = lockAndCheckVisible(id, user);
        if (!user.isSafetyOfficer()) {
            throw new ForbiddenException(String.format(
                    "重新开启被拒绝：仅安全主管可重新开启已封存事件，当前操作人「%s」是普通值班员", user.name()));
        }
        checkVersion(event, dto);
        if (!IncidentEvent.STATUS_SEALED.equals(event.getStatus())) {
            throw new com.px.base.security.BusinessConflictException(String.format(
                    "当前状态为「%s」，只有已封存事件能重新开启", IncidentLabels.status(event.getStatus())));
        }
        if (dto.getBasis() == null || dto.getBasis().isBlank()) {
            throw new IllegalArgumentException("重新开启必须写清依据（新证据/认定错误/整改需要等）");
        }

        int roundNo = event.getCurrentSealRound() == null ? 0 : event.getCurrentSealRound();
        IncidentSealRound round = sealRoundRepository.findByIncidentIdOrderByRoundNoAsc(event.getId()).stream()
                .filter(r -> r.getRoundNo().equals(roundNo))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("封存轮次数据缺失，无法重新开启"));

        String beforeJson = contentJson(event);
        String fromStatus = event.getStatus();
        event.setStatus(IncidentEvent.STATUS_REOPENED);
        event.setVersion(event.getVersion() + 1);
        event = eventRepository.save(event);

        LocalDateTime now = LocalDateTime.now();
        round.setReopenTime(now);
        round.setReopenedById(user.id());
        round.setReopenedByName(user.name());
        round.setReopenBasis(dto.getBasis().trim());
        round.setReopenVersion(event.getVersion());
        sealRoundRepository.save(round);

        appendRevision(event, IncidentRevision.TYPE_REOPEN, fromStatus,
                IncidentEvent.STATUS_REOPENED, user, dto.getBasis().trim(),
                beforeJson, contentJson(event), roundNo);

        log.warn("事件{}第{}轮封存被重新开启 by={} 依据={}",
                event.getIncidentCode(), roundNo, user.name(), dto.getBasis());
        return getView(event.getId());
    }

    private IncidentViewDTO transition(IncidentEvent event, String toStatus, String type,
                                       CurrentUser user, String reason) {
        String beforeJson = contentJson(event);
        String fromStatus = event.getStatus();
        event.setStatus(toStatus);
        event.setVersion(event.getVersion() + 1);
        event = eventRepository.save(event);
        appendRevision(event, type, fromStatus, toStatus, user, reason,
                beforeJson, contentJson(event), event.getCurrentSealRound());
        log.info("事件{}状态流转 {} -> {} ({}) by={} version={}",
                event.getIncidentCode(), fromStatus, toStatus, type, user.name(), event.getVersion());
        return getView(event.getId());
    }

    private void requireSealElements(IncidentEvent event, String action) {
        List<String> missing = new ArrayList<>();
        if (isBlank(event.getCauseConclusion())) missing.add("原因结论");
        if (isBlank(event.getCorrectiveAction())) missing.add("纠正措施");
        if (event.getOwnerId() == null) missing.add("负责人");
        if (event.getDueDate() == null) missing.add("整改期限");
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(String.format(
                    "无法%s：以下内容必须填写完整——%s", action, String.join("、", missing)));
        }
    }

    /* ============================== 查询 ============================== */

    @Transactional(readOnly = true)
    public List<IncidentListItemDTO> list() {
        CurrentUser user = currentUserResolver.require();
        List<IncidentEvent> all = eventRepository.findAllByOrderByFoundTimeDescIdDesc();
        return all.stream()
                .filter(e -> user.isSafetyOfficer() || isParticipant(user, e))
                .map(this::toListItem)
                .toList();
    }

    @Transactional(readOnly = true)
    public IncidentViewDTO get(Long id) {
        CurrentUser user = currentUserResolver.require();
        IncidentEvent event = eventRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("异常事件不存在: " + id));
        if (!user.isSafetyOfficer() && !isParticipant(user, event)) {
            // 不泄露存在性：无权限与不存在同样拒绝
            throw new ForbiddenException(String.format(
                    "无权查看该异常事件：普通值班员只能查看自己报告或参与的事件，当前操作人「%s」不在其列",
                    user.name()));
        }
        return toView(event, user);
    }

    /** 写操作完成后回读最新详情（调用方已经过可见性与权限校验） */
    private IncidentViewDTO getView(Long id) {
        IncidentEvent event = eventRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("异常事件不存在: " + id));
        return toView(event, currentUserResolver.currentOrNull());
    }

    /** 写操作专用：行级悲观写锁 + 可见性，锁内完成版本比对，杜绝丢失更新 */
    private IncidentEvent lockAndCheckVisible(Long id, CurrentUser user) {
        IncidentEvent event = eventRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("异常事件不存在: " + id));
        if (!user.isSafetyOfficer() && !isParticipant(user, event)) {
            throw new ForbiddenException(String.format(
                    "无权操作该异常事件：普通值班员只能操作自己报告或参与的事件，当前操作人「%s」不在其列",
                    user.name()));
        }
        return event;
    }

    private boolean isParticipant(CurrentUser user, IncidentEvent event) {
        if (user.id().equals(event.getReporterId())) {
            return true;
        }
        if (event.getInvolvedStaffIds() == null || event.getInvolvedStaffIds().isBlank()) {
            return false;
        }
        return Csv.split(event.getInvolvedStaffIds()).contains(String.valueOf(user.id()));
    }

    private void requireParticipant(CurrentUser user, IncidentEvent event, String action) {
        // 安全主管可查看全部，但正文修订/进入调查/提交封存属于调查动作：主管若不是报告人/参与人，
        // 仍可执行（主管统筹调查）——需求限定仅封存/重开专属主管，其余动作参与者与主管均可。
        if (user.isSafetyOfficer()) {
            return;
        }
        if (!isParticipant(user, event)) {
            throw new ForbiddenException(String.format(
                    "无权%s：只有报告人或事件涉及人员可以操作，当前操作人「%s」不在其列", action, user.name()));
        }
    }

    /* ============================== 版本口径 ============================== */

    private void checkVersion(IncidentEvent event, IncidentTransitionDTO dto) {
        if (dto.getExpectedVersion() == null) {
            throw new IllegalArgumentException("缺少版本号 expectedVersion：必须基于打开时看到的版本提交");
        }
        checkVersion(event, dto.getExpectedVersion(), IncidentContentDTO.of(event));
    }

    private void checkVersion(IncidentEvent event, long expectedVersion, IncidentContentDTO submitted) {
        if (!event.getVersion().equals(expectedVersion)) {
            throwVersionConflict(event, expectedVersion, submitted);
        }
    }

    /**
     * 整份版本冲突：把后到者提交的正文与最新事件逐字段比对，
     * 列出冲突字段，并把最新完整详情随 409 返回。提交内容不落库任何字段。
     */
    private void throwVersionConflict(IncidentEvent event, long expectedVersion, IncidentContentDTO submitted) {
        CurrentUser user = currentUserResolver.currentOrNull();
        IncidentViewDTO latest = toView(event, user);
        List<String> conflicts = new ArrayList<>(diffContent(submitted, IncidentContentDTO.of(event)));
        // 版本变化也可能仅来自状态流转（正文一字未改）：状态差异永远提示，避免给出“无冲突字段”的错觉
        if (!conflicts.contains(IncidentConflictDTO.FIELD_STATUS)) {
            conflicts.add(IncidentConflictDTO.FIELD_STATUS);
        }
        IncidentConflictDTO payload = IncidentConflictDTO.builder()
                .incidentId(event.getId())
                .incidentCode(event.getIncidentCode())
                .expectedVersion(expectedVersion)
                .latestVersion(event.getVersion())
                .latestStatus(event.getStatus())
                .latestStatusLabel(IncidentLabels.status(event.getStatus()))
                .conflictFields(conflicts)
                .latest(latest)
                .build();
        throw new IncidentVersionConflictException(String.format(
                "版本冲突：该事件已被其他人更新到 v%s（状态：%s），你提交的内容基于旧版本 v%s，已整份拒绝、未覆盖任何字段。"
                        + "冲突字段：%s。请查看最新内容后放弃，或基于最新版本重新编辑。",
                event.getVersion(), IncidentLabels.status(event.getStatus()), expectedVersion,
                String.join("、", conflicts)),
                payload);
    }

    /* ============================== 视图组装 ============================== */

    private IncidentListItemDTO toListItem(IncidentEvent e) {
        return IncidentListItemDTO.builder()
                .id(e.getId())
                .incidentCode(e.getIncidentCode())
                .title(e.getTitle())
                .status(e.getStatus())
                .statusLabel(IncidentLabels.status(e.getStatus()))
                .version(e.getVersion())
                .severity(e.getSeverity())
                .severityLabel(IncidentLabels.severity(e.getSeverity()))
                .foundTime(e.getFoundTime())
                .snapRouteCode(e.getSnapRouteCode())
                .snapRouteName(e.getSnapRouteName())
                .snapRouteWindLevel(e.getSnapRouteWindLevel())
                .reporterName(e.getReporterName())
                .involvedStaffNames(e.getInvolvedStaffNames())
                .currentSealRound(e.getCurrentSealRound())
                .revisionCount(revisionRepository.countByIncidentId(e.getId()))
                .createTime(e.getCreateTime())
                .updateTime(e.getUpdateTime())
                .build();
    }

    private IncidentViewDTO toView(IncidentEvent e, CurrentUser user) {
        boolean safety = user != null && user.isSafetyOfficer();
        boolean participant = user != null && isParticipant(user, e);
        String status = e.getStatus();

        List<IncidentRevisionViewDTO> revisions = revisionRepository
                .findByIncidentIdOrderBySeqAsc(e.getId()).stream()
                .map(this::toRevisionView)
                .toList();
        List<IncidentSealRoundViewDTO> rounds = sealRoundRepository
                .findByIncidentIdOrderByRoundNoAsc(e.getId()).stream()
                .map(this::toRoundView)
                .toList();

        boolean editor = safety || participant;
        IncidentViewDTO.IncidentViewDTOBuilder b = IncidentViewDTO.builder()
                .id(e.getId())
                .incidentCode(e.getIncidentCode())
                .title(e.getTitle())
                .status(status)
                .statusLabel(IncidentLabels.status(status))
                .version(e.getVersion())
                .severity(e.getSeverity())
                .severityLabel(IncidentLabels.severity(e.getSeverity()))
                .foundTime(e.getFoundTime())
                .sceneNarrative(e.getSceneNarrative())
                .handlingActions(e.getHandlingActions())
                .evidenceNote(e.getEvidenceNote())
                .causeConclusion(e.getCauseConclusion())
                .correctiveAction(e.getCorrectiveAction())
                .ownerId(e.getOwnerId())
                .ownerName(e.getOwnerName())
                .dueDate(e.getDueDate())
                .routeId(e.getRouteId())
                .snapRouteCode(e.getSnapRouteCode())
                .snapRouteName(e.getSnapRouteName())
                .snapRouteWindLevel(e.getSnapRouteWindLevel())
                .snapRouteWindSpeed(e.getSnapRouteWindSpeed())
                .watchId(e.getWatchId())
                .snapWatchTakeoff(e.getSnapWatchTakeoff())
                .snapWatchEnd(e.getSnapWatchEnd())
                .snapWatchOperatorId(e.getSnapWatchOperatorId())
                .snapWatchOperatorName(e.getSnapWatchOperatorName())
                .snapWatchReviewerId(e.getSnapWatchReviewerId())
                .snapWatchReviewerName(e.getSnapWatchReviewerName())
                .anchorSnapshots(anchorSnapshotRepository
                        .findByIncidentIdOrderBySortNoAscIdAsc(e.getId()).stream()
                        .map(this::toAnchorView).toList())
                .reporterId(e.getReporterId())
                .reporterName(e.getReporterName())
                .involvedStaffIds(parseIdList(e.getInvolvedStaffIds()))
                .involvedStaffNames(e.getInvolvedStaffNames())
                .currentSealRound(e.getCurrentSealRound())
                .revisions(revisions)
                .sealRounds(rounds)
                .sealed(IncidentEvent.STATUS_SEALED.equals(status))
                .canEditContent(editor && (
                        IncidentEvent.STATUS_DRAFT.equals(status)
                                || IncidentEvent.STATUS_INVESTIGATING.equals(status)
                                || IncidentEvent.STATUS_REOPENED.equals(status)))
                .canEnterInvestigating(editor && IncidentEvent.STATUS_DRAFT.equals(status))
                .canRequestSeal(editor && (
                        IncidentEvent.STATUS_INVESTIGATING.equals(status)
                                || IncidentEvent.STATUS_REOPENED.equals(status)))
                .canBackToInvestigating(editor && IncidentEvent.STATUS_PENDING_SEAL.equals(status))
                .canSeal(safety && IncidentEvent.STATUS_PENDING_SEAL.equals(status))
                .canReopen(safety && IncidentEvent.STATUS_SEALED.equals(status))
                .createTime(e.getCreateTime())
                .updateTime(e.getUpdateTime())
                .policy(IncidentViewDTO.POLICY)
                .rejectedAlternative(IncidentViewDTO.REJECTED_ALTERNATIVE);

        fillCurrentRoute(b, e);
        fillCurrentWatch(b, e);
        return b.build();
    }

    private void fillCurrentRoute(IncidentViewDTO.IncidentViewDTOBuilder b, IncidentEvent e) {
        Optional<FlightRoute> cur = routeRepository.findById(e.getRouteId());
        if (cur.isEmpty()) {
            b.currentRouteExists(false).routeDiffFields(List.of());
            return;
        }
        FlightRoute r = cur.get();
        List<String> diff = new ArrayList<>();
        if (!Objects.equals(e.getSnapRouteName(), r.getRouteName())) diff.add("航线名称");
        if (!Objects.equals(e.getSnapRouteCode(), r.getRouteCode())) diff.add("航线编号");
        if (!Objects.equals(e.getSnapRouteWindLevel(), r.getWindLevel())) diff.add("风级");
        if (!Objects.equals(e.getSnapRouteWindSpeed(), r.getWindSpeed())) diff.add("风速");
        b.currentRouteExists(true)
                .currentRouteCode(r.getRouteCode())
                .currentRouteName(r.getRouteName())
                .currentRouteWindLevel(r.getWindLevel())
                .currentRouteWindSpeed(r.getWindSpeed())
                .currentRouteStatus(r.getStatus())
                .routeDiffFields(diff);
    }

    private void fillCurrentWatch(IncidentViewDTO.IncidentViewDTOBuilder b, IncidentEvent e) {
        if (e.getWatchId() == null) {
            b.currentWatchExists(false).watchDiffFields(List.of());
            return;
        }
        Optional<FlightWatch> cur = watchRepository.findById(e.getWatchId());
        if (cur.isEmpty()) {
            b.currentWatchExists(false).watchDiffFields(List.of());
            return;
        }
        FlightWatch w = cur.get();
        List<String> diff = new ArrayList<>();
        if (!Objects.equals(e.getSnapWatchOperatorName(), w.getOperatorName())) diff.add("操作员姓名");
        if (!Objects.equals(e.getSnapWatchReviewerName(), w.getReviewerName())) diff.add("复核员姓名");
        if (!Objects.equals(e.getSnapWatchTakeoff(), w.getPlannedTakeoff())) diff.add("起飞时刻");
        b.currentWatchExists(true)
                .currentWatchStatus(w.getStatus())
                .currentWatchStatusLabel(FlightWatchService.statusLabel(w.getStatus()))
                .currentWatchOperatorName(w.getOperatorName())
                .currentWatchReviewerName(w.getReviewerName())
                .watchDiffFields(diff);
    }

    private IncidentAnchorViewDTO toAnchorView(IncidentAnchorSnapshot s) {
        Optional<Anchor> cur = anchorRepository.findById(s.getAnchorId());
        IncidentAnchorViewDTO.IncidentAnchorViewDTOBuilder b = IncidentAnchorViewDTO.builder()
                .anchorId(s.getAnchorId())
                .anchorCode(s.getAnchorCode())
                .locationDesc(s.getLocationDesc())
                .anchorZone(s.getAnchorZone())
                .anchorStatus(s.getAnchorStatus())
                .anchorStatusLabel(IncidentLabels.anchorStatus(s.getAnchorStatus()))
                .maxWeight(s.getMaxWeight())
                .minWindSpeed(s.getMinWindSpeed())
                .maxWindSpeed(s.getMaxWindSpeed());
        if (cur.isEmpty()) {
            b.currentExists(false).diffFields(List.of("锚点档案已删除"));
            return b.build();
        }
        Anchor a = cur.get();
        List<String> diff = new ArrayList<>();
        if (!Objects.equals(s.getAnchorCode(), a.getAnchorCode())) diff.add("锚点编号");
        if (!Objects.equals(s.getLocationDesc(), a.getLocationDesc())) diff.add("位置描述");
        if (!Objects.equals(s.getAnchorZone(), a.getAnchorZone())) diff.add("所属区域");
        if (!Objects.equals(s.getAnchorStatus(), a.getStatus())) diff.add("锚点状态");
        if (!Objects.equals(s.getMaxWeight(), a.getMaxWeight())) diff.add("最大承重");
        if (!Objects.equals(s.getMinWindSpeed(), a.getMinWindSpeed())) diff.add("气流下限");
        if (!Objects.equals(s.getMaxWindSpeed(), a.getMaxWindSpeed())) diff.add("气流上限");
        b.currentExists(true)
                .currentAnchorCode(a.getAnchorCode())
                .currentLocationDesc(a.getLocationDesc())
                .currentAnchorZone(a.getAnchorZone())
                .currentAnchorStatus(a.getStatus())
                .currentAnchorStatusLabel(IncidentLabels.anchorStatus(a.getStatus()))
                .currentMaxWeight(a.getMaxWeight())
                .currentMinWindSpeed(a.getMinWindSpeed())
                .currentMaxWindSpeed(a.getMaxWindSpeed())
                .diffFields(diff);
        return b.build();
    }

    private IncidentRevisionViewDTO toRevisionView(IncidentRevision r) {
        List<String> changed = changedFields(r.getBeforeContent(), r.getAfterContent());
        return IncidentRevisionViewDTO.builder()
                .id(r.getId())
                .seq(r.getSeq())
                .revisionType(r.getRevisionType())
                .revisionTypeLabel(IncidentLabels.revisionType(r.getRevisionType()))
                .fromStatus(r.getFromStatus())
                .fromStatusLabel(IncidentLabels.status(r.getFromStatus()))
                .toStatus(r.getToStatus())
                .toStatusLabel(IncidentLabels.status(r.getToStatus()))
                .operatorId(r.getOperatorId())
                .operatorName(r.getOperatorName())
                .operatorRole(r.getOperatorRole())
                .operatorRoleLabel(IncidentLabels.role(r.getOperatorRole()))
                .reason(r.getReason())
                .beforeContent(r.getBeforeContent())
                .afterContent(r.getAfterContent())
                .changedFields(changed)
                .sealRound(r.getSealRound())
                .operateTime(r.getOperateTime())
                .build();
    }

    private IncidentSealRoundViewDTO toRoundView(IncidentSealRound r) {
        return IncidentSealRoundViewDTO.builder()
                .roundNo(r.getRoundNo())
                .sealTime(r.getSealTime())
                .sealedById(r.getSealedById())
                .sealedByName(r.getSealedByName())
                .sealedVersion(r.getSealedVersion())
                .reopenTime(r.getReopenTime())
                .reopenedById(r.getReopenedById())
                .reopenedByName(r.getReopenedByName())
                .reopenBasis(r.getReopenBasis())
                .reopenVersion(r.getReopenVersion())
                .reopened(r.getReopenTime() != null)
                .build();
    }

    /* ============================== 正文 JSON 与字段比对 ============================== */

    private String contentJson(IncidentEvent e) {
        return writeJson(IncidentContentDTO.of(e));
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("正文序列化失败", ex);
        }
    }

    private void applyContent(IncidentEvent e, IncidentContentDTO c) {
        e.setTitle(c.getTitle().trim());
        e.setSeverity(c.getSeverity());
        e.setFoundTime(parseDateTime(c.getFoundTime(), "发现时间"));
        e.setSceneNarrative(trimToNull(c.getSceneNarrative()));
        e.setHandlingActions(trimToNull(c.getHandlingActions()));
        e.setEvidenceNote(trimToNull(c.getEvidenceNote()));
        e.setCauseConclusion(trimToNull(c.getCauseConclusion()));
        e.setCorrectiveAction(trimToNull(c.getCorrectiveAction()));
        if (c.getOwnerId() != null) {
            GroundStaff owner = staffRepository.findById(c.getOwnerId())
                    .orElseThrow(() -> new IllegalArgumentException("整改负责人不存在: " + c.getOwnerId()));
            e.setOwnerId(owner.getId());
            e.setOwnerName(owner.getStaffName());
        } else {
            e.setOwnerId(null);
            e.setOwnerName(null);
        }
        e.setDueDate(c.getDueDate());
    }

    /**
     * 逐字段比对两份正文 JSON，返回值不一致字段的中文名（整份口径下用于冲突提示与修订高亮）。
     */
    private List<String> diffContent(IncidentContentDTO submitted, IncidentContentDTO latest) {
        return changedFields(writeJson(submitted), writeJson(latest));
    }

    private List<String> changedFields(String beforeJson, String afterJson) {
        List<String> changed = new ArrayList<>();
        JsonNode before = parseOrMissing(beforeJson);
        JsonNode after = parseOrMissing(afterJson);
        for (Map.Entry<String, String> f : CONTENT_FIELDS.entrySet()) {
            JsonNode bv = before == null ? null : before.get(f.getKey());
            JsonNode av = after == null ? null : after.get(f.getKey());
            if (!jsonValueEquals(bv, av)) {
                changed.add(f.getValue());
            }
        }
        return changed;
    }

    private JsonNode parseOrMissing(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private boolean jsonValueEquals(JsonNode a, JsonNode b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) {
            // null 与空串等价（文本字段未填）
            return (a == null || a.isNull() || (a.isTextual() && a.asText().isEmpty()))
                    && (b == null || b.isNull() || (b.isTextual() && b.asText().isEmpty()));
        }
        return Objects.equals(a, b);
    }

    private void appendRevision(IncidentEvent event, String type, String fromStatus, String toStatus,
                                CurrentUser user, String reason, String beforeJson, String afterJson,
                                int sealRound) {
        int seq = revisionRepository.countByIncidentId(event.getId());
        revisionRepository.save(IncidentRevision.builder()
                .incidentId(event.getId())
                .seq(seq)
                .revisionType(type)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .operatorId(user.id())
                .operatorName(user.name())
                .operatorRole(user.role())
                .reason(reason)
                .beforeContent(beforeJson)
                .afterContent(afterJson)
                .sealRound(sealRound)
                .operateTime(LocalDateTime.now())
                .build());
    }

    /* ============================== 杂项 ============================== */

    private List<Long> parseIdList(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Csv.split(csv).stream().map(Long::valueOf).toList();
    }

    private String generateCode() {
        return "INC-" + LocalDateTime.now().format(CODE_FMT)
                + String.format("%02d", ThreadLocalRandom.current().nextInt(100));
    }

    private static String trimToNull(String s) {
        return s == null ? null : (s.trim().isEmpty() ? null : s.trim());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static LocalDateTime parseDateTime(String s, String field) {
        try {
            return LocalDateTime.parse(s.trim());
        } catch (Exception ex) {
            throw new IllegalArgumentException(field + "格式无效，应为 2026-09-23T14:30:00");
        }
    }

    private static LocalDate parseDate(String s, String field) {
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception ex) {
            throw new IllegalArgumentException(field + "格式无效，应为 2026-09-23");
        }
    }
}
