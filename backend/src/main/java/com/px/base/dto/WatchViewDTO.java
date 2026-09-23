package com.px.base.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 值守详情视图。
 *
 * 非终态（DRAFT/PENDING_REVIEW）：operator/reviewer 姓名取实时档案，
 *   operatorQualification/reviewerQualification 按起飞时刻实时重算，
 *   requiredWindLevel/requiredZones 取航线当前值，缺口逐项给出。
 * 终态（READY/CANCELLED）：全部改读 *Snapshot 字段，显示就绪当时采用的
 *   证书编号、适用范围与人员姓名，不随后续人员改名/证书吊销而变化。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatchViewDTO {
    private Long id;
    private Long routeId;
    private String routeCode;
    private String routeName;
    private LocalDate flightDate;
    private LocalDateTime plannedTakeoff;
    private LocalDateTime plannedEnd;

    private String status;
    private String statusLabel;
    private Integer operatorArrived;
    private LocalDateTime arrivalTime;
    private Integer reviewerArrived;
    private LocalDateTime reviewerArrivalTime;
    private LocalDateTime readyTime;
    private String readiedByName;
    private LocalDateTime cancelTime;
    private String cancelledByName;
    private String cancelReason;
    private String requalifyReason;

    private Long operatorId;
    private String operatorName;
    private Long reviewerId;
    private String reviewerName;

    /** 实时资质结论（终态为 null，改看快照） */
    private QualificationView operatorQualification;
    private QualificationView reviewerQualification;

    /** 实时航线要求（终态为 null） */
    private String requiredWindLevel;
    private List<String> requiredZones;

    /** 两人是否不同人 */
    private boolean distinctPeople;
    /** 复核员能否就绪：操作员已到位 且 复核员已到位 且 两人资质均合格 且 非终态 */
    private boolean canReady;
    /** 当前是否为历史（预计起飞时刻已过） */
    private boolean past;

    /* ---------------- 就绪快照（历史） ---------------- */
    private boolean hasSnapshot;
    private LocalDateTime snapshotTakeoff;
    private LocalDateTime snapshotEnd;
    private String snapshotRouteWindLevel;
    private List<String> snapshotRequiredZones;
    private String operatorSnapshotName;
    private String operatorSnapshotCertNo;
    private String operatorSnapshotScope;
    private String reviewerSnapshotName;
    private String reviewerSnapshotCertNo;
    private String reviewerSnapshotScope;

    /** 判定口径说明（三处入口统一文案） */
    private String policy;
}
