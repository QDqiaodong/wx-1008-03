package com.px.base.incident.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 飞行异常事件。
 *
 * 状态链：
 *   DRAFT 草稿（报告人可补充）
 *     --开始调查--> INVESTIGATING 调查中（每次修订留痕：操作者/时间/前后内容）
 *     --调查完成--> PENDING_SEAL 待封存（原因结论/纠正措施/负责人/期限必填）
 *     --安全主管封存--> SEALED 已封存（正文与原证据不可覆盖，更正只能追加带理由的新修订）
 *     --安全主管写明依据重新开启--> REOPENED 重新开启（可再调查、再次封存；
 *                                     第二次封存后可从修订流水看出两次封存之间发生了什么）
 *   REOPENED --安全主管再次封存--> SEALED
 *
 * 并发口径：整份事件版本冲突（乐观锁）。所有写操作必须携带 expectedVersion，
 * 服务端在同一事务内比对 version，不一致即抛 409 并附带冲突字段与最新版本，
 * 后到编辑不会静默覆盖先到内容。不采用按字段合并的原因见 IncidentService.POLICY。
 */
@Entity
@Table(name = "incident")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Incident {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_INVESTIGATING = "INVESTIGATING";
    public static final String STATUS_PENDING_SEAL = "PENDING_SEAL";
    public static final String STATUS_SEALED = "SEALED";
    public static final String STATUS_REOPENED = "REOPENED";

    public static final String SEVERITY_MINOR = "MINOR";
    public static final String SEVERITY_MAJOR = "MAJOR";
    public static final String SEVERITY_CRITICAL = "CRITICAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_no", unique = true, nullable = false, length = 50)
    private String incidentNo;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** 关联的值守安排（可为空：飞行结束后补录时允许只关联航线） */
    @Column(name = "watch_id")
    private Long watchId;

    /** 关联航线：始终保留 ID 以便跳转当前资料；事发名称/风级在修订与建事件时冻结 */
    @Column(name = "route_id", nullable = false)
    private Long routeId;

    @Column(name = "found_time", nullable = false)
    private LocalDateTime foundTime;

    @Column(name = "severity", nullable = false, length = 20)
    private String severity;

    @Column(name = "incident_note", nullable = false, length = 4000)
    private String incidentNote;

    @Column(name = "handling_action", length = 4000)
    private String handlingAction;

    @Column(name = "evidence_desc", length = 2000)
    private String evidenceDesc;

    @Column(name = "root_cause", length = 4000)
    private String rootCause;

    @Column(name = "corrective_action", length = 4000)
    private String correctiveAction;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "owner_name", length = 50)
    private String ownerName;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "reporter_id", nullable = false)
    private Long reporterId;

    @Column(name = "reporter_name", nullable = false, length = 50)
    private String reporterName;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_DRAFT;

    /**
     * 乐观锁版本（显式校验，不依赖 @Version：
     * 我们要在 409 响应里带回冲突字段与最新版本，且要求“状态变更+修订留痕”同事务）。
     * 每次新增修订 +1，与 incident_revision.revision_no 保持一致。
     */
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 1;

    @Column(name = "sealed_count", nullable = false)
    @Builder.Default
    private Integer sealedCount = 0;

    @Column(name = "current_revision_id")
    private Long currentRevisionId;

    @Column(name = "first_seal_time")
    private LocalDateTime firstSealTime;

    @Column(name = "last_seal_time")
    private LocalDateTime lastSealTime;

    @Column(name = "sealed_by_id")
    private Long sealedById;

    @Column(name = "sealed_by_name", length = 50)
    private String sealedByName;

    /* ---------------- 建事件时冻结的关联对象快照（永不修改） ---------------- */

    @Column(name = "snapshot_route_code", length = 50)
    private String snapshotRouteCode;

    @Column(name = "snapshot_route_name", length = 100)
    private String snapshotRouteName;

    @Column(name = "snapshot_route_wind_level", length = 20)
    private String snapshotRouteWindLevel;

    @Column(name = "snapshot_route_group", length = 50)
    private String snapshotRouteGroup;

    @Column(name = "snapshot_watch_flight_date", length = 20)
    private String snapshotWatchFlightDate;

    @Column(name = "snapshot_watch_takeoff", length = 30)
    private String snapshotWatchTakeoff;

    @Column(name = "snapshot_operator_name", length = 50)
    private String snapshotOperatorName;

    @Column(name = "snapshot_reviewer_name", length = 50)
    private String snapshotReviewerName;

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

    public boolean isSealed() {
        return STATUS_SEALED.equals(status);
    }

    /** 封存所需四项是否齐备（待封存/封存闸门用） */
    public boolean hasSealRequiredFields() {
        return notBlank(rootCause) && notBlank(correctiveAction)
                && ownerId != null && dueDate != null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
