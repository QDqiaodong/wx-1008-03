package com.px.base.incident.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 事件不可变修订流水（只追加，永不 UPDATE/DELETE）。
 *
 * 每一行是“某个版本下事件正文的完整快照 + 状态前后值 + 操作者/时间/理由”：
 *  - 相邻两行天然构成前后内容对比（修订记录页并排展示 N-1 与 N 两版）；
 *  - CREATE 建事件即第 1 版；EDIT 调查中修订；SEAL 封存；CORRECTION 封存后更正；
 *    REOPEN 重新开启。
 *  - 封存后的正文与原证据不能直接覆盖：任何更正都必须新增一行 CORRECTION 并写理由。
 *
 * 状态变更与本行插入由 IncidentService 在同一 @Transactional 内完成，
 * 不可能出现“状态已封存但修订历史缺失”的半成品。
 */
@Entity
@Table(name = "incident_revision")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentRevision {

    public static final String TYPE_CREATE = "CREATE";
    public static final String TYPE_EDIT = "EDIT";
    public static final String TYPE_SEAL = "SEAL";
    public static final String TYPE_CORRECTION = "CORRECTION";
    public static final String TYPE_REOPEN = "REOPEN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_id", nullable = false)
    private Long incidentId;

    @Column(name = "revision_no", nullable = false)
    private Integer revisionNo;

    @Column(name = "change_type", nullable = false, length = 30)
    private String changeType;

    /** 修订理由：封存后更正、重新开启必填 */
    @Column(name = "change_reason", length = 1000)
    private String changeReason;

    /* ----- 该版本完整正文（不可变快照） ----- */

    @Column(name = "title", nullable = false, length = 200)
    private String title;

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

    @Column(name = "status_before", length = 20)
    private String statusBefore;

    @Column(name = "status_after", length = 20)
    private String statusAfter;

    @Column(name = "operator_id", nullable = false)
    private Long operatorId;

    @Column(name = "operator_name", nullable = false, length = 50)
    private String operatorName;

    @Column(name = "create_time", updatable = false)
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
    }
}
