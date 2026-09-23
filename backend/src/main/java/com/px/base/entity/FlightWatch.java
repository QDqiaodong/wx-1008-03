package com.px.base.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 开航值守：按航线 + 飞行日安排一名操作员与一名复核员。
 *
 * 状态链：
 *   DRAFT 草拟 --操作员到位确认--> PENDING_REVIEW 待复核
 *   PENDING_REVIEW --(操作员已到位 + 两人资质实时均覆盖)--> READY 就绪（同时冻结证书快照）
 *   任意未就绪态 --取消--> CANCELLED（终态）
 *   READY 取消仅安全主管可操作（CANCELLED 终态）
 *
 * READY / CANCELLED 为终态：证书到期或被吊销不会再改它们；
 * 仅 PENDING_REVIEW / DRAFT 且飞行日在未来的安排会被重新判定打回 DRAFT。
 *
 * 就绪时冻结 *_snapshot_* 字段，历史记录永远显示当时采用的证书编号、
 * 适用范围与人员姓名，不随后续人员档案/证书变化而变化。
 */
@Entity
@Table(name = "flight_watch")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlightWatch {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "route_id", nullable = false)
    private Long routeId;

    @Column(name = "route_code", length = 50)
    private String routeCode;

    @Column(name = "flight_date", nullable = false)
    private LocalDate flightDate;

    /** 预计起飞时刻：跨午夜任务的证书有效性统一按此刻判定 */
    @Column(name = "planned_takeoff", nullable = false)
    private LocalDateTime plannedTakeoff;

    /** 预计结束时刻（可晚于起飞时刻跨午夜，仅展示，不参与证书判定） */
    @Column(name = "planned_end")
    private LocalDateTime plannedEnd;

    @Column(name = "operator_id", nullable = false)
    private Long operatorId;

    @Column(name = "operator_name", length = 50)
    private String operatorName;

    @Column(name = "reviewer_id", nullable = false)
    private Long reviewerId;

    @Column(name = "reviewer_name", length = 50)
    private String reviewerName;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_DRAFT;

    @Column(name = "operator_arrived")
    @Builder.Default
    private Integer operatorArrived = 0;

    @Column(name = "arrival_time")
    private LocalDateTime arrivalTime;

    @Column(name = "reviewer_arrived")
    @Builder.Default
    private Integer reviewerArrived = 0;

    @Column(name = "reviewer_arrival_time")
    private LocalDateTime reviewerArrivalTime;

    @Column(name = "ready_time")
    private LocalDateTime readyTime;

    @Column(name = "readied_by_id")
    private Long readiedById;

    @Column(name = "readied_by_name", length = 50)
    private String readiedByName;

    @Column(name = "cancel_time")
    private LocalDateTime cancelTime;

    @Column(name = "cancelled_by_id")
    private Long cancelledById;

    @Column(name = "cancelled_by_name", length = 50)
    private String cancelledByName;

    @Column(name = "cancel_reason", length = 300)
    private String cancelReason;

    /** 最近一次被重新判定打回的原因（证书吊销/到期、航线风级或锚点区域变化等） */
    @Column(name = "requalify_reason", length = 1000)
    private String requalifyReason;

    /* ---------------- 就绪时冻结的历史快照（终态后永不修改） ---------------- */

    @Column(name = "snapshot_takeoff")
    private LocalDateTime snapshotTakeoff;

    @Column(name = "snapshot_end")
    private LocalDateTime snapshotEnd;

    @Column(name = "snapshot_route_wind_level", length = 20)
    private String snapshotRouteWindLevel;

    @Column(name = "snapshot_required_zones", length = 500)
    private String snapshotRequiredZones;

    @Column(name = "operator_snapshot_name", length = 50)
    private String operatorSnapshotName;

    @Column(name = "operator_snapshot_cert_no", length = 50)
    private String operatorSnapshotCertNo;

    @Column(name = "operator_snapshot_scope", length = 1000)
    private String operatorSnapshotScope;

    @Column(name = "reviewer_snapshot_name", length = 50)
    private String reviewerSnapshotName;

    @Column(name = "reviewer_snapshot_cert_no", length = 50)
    private String reviewerSnapshotCertNo;

    @Column(name = "reviewer_snapshot_scope", length = 1000)
    private String reviewerSnapshotScope;

    @Column(name = "create_time", updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }

    public boolean isTerminal() {
        return STATUS_READY.equals(status) || STATUS_CANCELLED.equals(status);
    }

    public boolean isReady() {
        return STATUS_READY.equals(status);
    }

    public boolean isCancelled() {
        return STATUS_CANCELLED.equals(status);
    }
}
