package com.px.base.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 事件修订记录（只追加，永不更新/删除）。
 *
 * seq=0 为建档记录；此后每条正文修订或状态流转一条，seq 递增。
 * before_content / after_content 保存该次修订【整份正文】的 JSON 快照，
 * 因此任意两个版本都能并排对比，封存后更正也只是“新增一条 CORRECT 记录”，
 * 旧记录原封不动——封存正文与原证据不可能被覆盖。
 *
 * 状态变更与修订记录在同一个数据库事务内提交：
 * 要么事件状态与修订历史一起落库，要么一起回滚，不存在“已封存但修订历史缺失”。
 */
@Entity
@Table(name = "incident_revision")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentRevision {

    /** 建档 */
    public static final String TYPE_CREATE = "CREATE";
    /** 草稿/调查中/重新开启态的正文修订 */
    public static final String TYPE_EDIT = "EDIT";
    /** 进入调查中 */
    public static final String TYPE_ENTER_INVESTIGATING = "ENTER_INVESTIGATING";
    /** 提交待封存 */
    public static final String TYPE_REQUEST_SEAL = "REQUEST_SEAL";
    /** 安全主管封存 */
    public static final String TYPE_SEAL = "SEAL";
    /** 安全主管重新开启 */
    public static final String TYPE_REOPEN = "REOPEN";
    /** 封存后更正（只新增带理由的新版本，不动旧版） */
    public static final String TYPE_CORRECT = "CORRECT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_id", nullable = false)
    private Long incidentId;

    @Column(name = "seq", nullable = false)
    private Integer seq;

    @Column(name = "revision_type", nullable = false, length = 30)
    private String revisionType;

    @Column(name = "from_status", length = 20)
    private String fromStatus;

    @Column(name = "to_status", length = 20)
    private String toStatus;

    @Column(name = "operator_id", nullable = false)
    private Long operatorId;

    @Column(name = "operator_name", nullable = false, length = 50)
    private String operatorName;

    @Column(name = "operator_role", nullable = false, length = 20)
    private String operatorRole;

    /** 修订理由 / 状态流转依据；封存后更正、重新开启必填 */
    @Column(name = "reason", length = 1000)
    private String reason;

    /** 修订前完整正文 JSON（CREATE 时为 null） */
    @Column(name = "before_content", columnDefinition = "TEXT")
    private String beforeContent;

    /** 修订后完整正文 JSON（状态流转若正文不变则与 before 相同，但仍完整留存） */
    @Column(name = "after_content", columnDefinition = "TEXT")
    private String afterContent;

    /** 该条修订发生时事件所处的封存轮次 */
    @Column(name = "seal_round")
    @Builder.Default
    private Integer sealRound = 0;

    @Column(name = "operate_time", nullable = false)
    private LocalDateTime operateTime;
}
