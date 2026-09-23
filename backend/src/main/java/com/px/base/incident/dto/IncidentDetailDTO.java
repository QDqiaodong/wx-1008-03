package com.px.base.incident.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件详情。四块口径严格分开：
 *  1) 当前可编辑正文（= 最新修订的内容）；
 *  2) 事发时冻结快照（航线名/风级、值守人员、锚点状态，建事件后永不变化）；
 *  3) 当前资料（实时查库，供“跳转/对比差异”，可能已删除/改名）；
 *  4) 版本与权限口径（version 是整份事件的乐观锁，按钮显隐只是提示，以后端为准）。
 */
@Data
@Builder
public class IncidentDetailDTO {
    private Long id;
    private String incidentNo;
    private String title;
    private Long watchId;
    private Long routeId;
    private LocalDateTime foundTime;
    private String severity;
    private String severityLabel;
    private String incidentNote;
    private String handlingAction;
    private String evidenceDesc;
    private String rootCause;
    private String correctiveAction;
    private Long ownerId;
    private String ownerName;
    private LocalDate dueDate;
    private Long reporterId;
    private String reporterName;
    private String status;
    private String statusLabel;
    private Integer version;
    private Integer sealedCount;
    private LocalDateTime firstSealTime;
    private LocalDateTime lastSealTime;
    private String sealedByName;
    private LocalDateTime createTime;

    /* ---------- 事发时冻结快照 ---------- */
    private String snapshotRouteCode;
    private String snapshotRouteName;
    private String snapshotRouteWindLevel;
    private String snapshotRouteGroup;
    private String snapshotWatchFlightDate;
    private String snapshotWatchTakeoff;
    private String snapshotOperatorName;
    private String snapshotReviewerName;
    private List<IncidentAnchorViewDTO> anchors;

    /* ---------- 当前资料（实时，对比差异用） ---------- */
    private Boolean currentRouteExists;
    private String currentRouteCode;
    private String currentRouteName;
    private String currentRouteWindLevel;
    private String currentRouteStatus;
    private Boolean routeChanged;
    private Boolean currentWatchExists;
    private String currentWatchStatus;
    private String currentOperatorName;
    private String currentReviewerName;
    private Boolean staffChanged;

    /* ---------- 权限（后端判定的真实口径，前端仅用于显隐） ---------- */
    private Boolean canEdit;
    private Boolean canStartInvestigation;
    private Boolean canSubmitSeal;
    private Boolean canBackToInvestigating;
    private Boolean canSeal;
    private Boolean canReopen;
    private Boolean canCorrect;
    private String sealBlockReason;

    /** 并发口径说明（页面固定展示，含为何不采用按字段合并） */
    private String concurrencyPolicy;
    private String fieldMergeRejectedReason;
}
