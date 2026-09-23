package com.px.base.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 飞行异常事件。
 *
 * 状态链：
 *   DRAFT 草稿 --报告人/参与人补充--> DRAFT（仅正文修订）
 *   DRAFT --进入调查--> INVESTIGATING 调查中（此后每次修订都留操作者/时间/前后内容）
 *   INVESTIGATING --提交封存--> PENDING_SEAL 待封存（必须已填原因结论/纠正措施/负责人/期限）
 *   PENDING_SEAL --安全主管封存--> SEALED 已封存（正文与证据不可变）
 *   SEALED --安全主管凭依据重新开启--> REOPENED 重新开启
 *   REOPENED --可继续更正--> PENDING_SEAL --安全主管再次封存--> SEALED（封存轮次 +1）
 *
 * 并发口径（整份事件版本冲突）：
 *   version 从 0 起单调递增；正文修订与每一次状态流转都 +1。
 *   写请求必须携带打开详情时看到的 version（expectedVersion），
 *   服务端比对不一致即整体拒绝（409），并返回冲突字段清单与最新版本，
 *   由后到者决定放弃还是基于最新内容重新编辑——绝不静默覆盖。
 *
 * 快照：建档时冻结航线名称/风级、值守人员姓名、涉及锚点状态等，
 *   存于本表 snap_* 列与 incident_anchor_snapshot；之后基础资料怎么改，历史事件不变。
 */
@Entity
@Table(name = "incident_event")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentEvent {

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

    @Column(name = "incident_code", unique = true, nullable = false, length = 50)
    private String incidentCode;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_DRAFT;

    /** 整份事件乐观版本号：任何正文修订或状态变更后 +1，并发判定唯一口径 */
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    @Column(name = "severity", nullable = false, length = 20)
    private String severity;

    @Column(name = "found_time", nullable = false)
    private LocalDateTime foundTime;

    /* ---------------- 正文（最新值；历次内容在 incident_revision 不可变保存） ---------------- */

    @Column(name = "scene_narrative", columnDefinition = "TEXT")
    private String sceneNarrative;

    @Column(name = "handling_actions", columnDefinition = "TEXT")
    private String handlingActions;

    @Column(name = "evidence_note", columnDefinition = "TEXT")
    private String evidenceNote;

    @Column(name = "cause_conclusion", columnDefinition = "TEXT")
    private String causeConclusion;

    @Column(name = "corrective_action", columnDefinition = "TEXT")
    private String correctiveAction;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "owner_name", length = 50)
    private String ownerName;

    @Column(name = "due_date")
    private LocalDate dueDate;

    /* ---------------- 关联对象与建档快照 ---------------- */

    @Column(name = "route_id", nullable = false)
    private Long routeId;

    @Column(name = "snap_route_code", nullable = false, length = 50)
    private String snapRouteCode;

    @Column(name = "snap_route_name", nullable = false, length = 100)
    private String snapRouteName;

    @Column(name = "snap_route_wind_level", length = 20)
    private String snapRouteWindLevel;

    @Column(name = "snap_route_wind_speed", precision = 5, scale = 2)
    private BigDecimal snapRouteWindSpeed;

    @Column(name = "watch_id")
    private Long watchId;

    @Column(name = "snap_watch_takeoff")
    private LocalDateTime snapWatchTakeoff;

    @Column(name = "snap_watch_end")
    private LocalDateTime snapWatchEnd;

    @Column(name = "snap_watch_operator_id")
    private Long snapWatchOperatorId;

    @Column(name = "snap_watch_operator_name", length = 50)
    private String snapWatchOperatorName;

    @Column(name = "snap_watch_reviewer_id")
    private Long snapWatchReviewerId;

    @Column(name = "snap_watch_reviewer_name", length = 50)
    private String snapWatchReviewerName;

    @Column(name = "reporter_id", nullable = false)
    private Long reporterId;

    @Column(name = "reporter_name", nullable = false, length = 50)
    private String reporterName;

    /** 涉及人员ID，逗号分隔——普通值班员的可见口径：报告人或在此列中 */
    @Column(name = "involved_staff_ids", length = 500)
    private String involvedStaffIds;

    @Column(name = "involved_staff_names", length = 1000)
    private String involvedStaffNames;

    /** 当前封存轮次：0 从未封存；重新开启后保持原值，再次封存 +1 */
    @Column(name = "current_seal_round")
    @Builder.Default
    private Integer currentSealRound = 0;

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
}
