package com.px.base.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件详情视图：正文 + 事发快照 + 当前档案对比 + 修订历史 + 当前操作者权限口径。
 *
 * 快照字段（snap* / anchorSnapshots / reporterName / involvedStaffNames / ownerName）
 * 在建档或修订当时冻结，刷新、重新登录、基础资料被修改后保持一致；
 * current* 字段仅用于“跳到当前资料查看差异”，不构成事件内容。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentViewDTO {

    public static final String POLICY =
            "并发口径：整份事件版本冲突——每次修改都必须基于打开时的版本号确认，"
            + "别人先改后你的整份提交会被拒绝，并列出冲突字段与最新版本，需放弃或基于最新内容重新编辑；"
            + "系统不做按字段静默合并。封存后正文与证据不可覆盖，更正只能形成带理由的新修订。";

    public static final String REJECTED_ALTERNATIVE =
            "不采用“按字段合并”的原因：异常事件是追责与整改依据，现场经过/证据/结论各字段语义相互引用，"
            + "按字段自动合并会把两个人分别基于旧事实写下的句子拼成一份谁都没确认过的正文，"
            + "甚至把“已封存”与“更正中”的字段混在一屏，破坏封存不可变；"
            + "且字段级合并仍需逐字段版本向量，详情页/修订记录/持久化三处口径容易不一致。"
            + "整份版本冲突策略只有一个单调版本号，详情页、修订历史与数据库行锁共用同一口径，"
            + "冲突必被人看见、解决过程本身也会形成新修订，满足复盘记录的可追溯要求。";

    private Long id;
    private String incidentCode;
    private String title;
    private String status;
    private String statusLabel;
    private Long version;
    private String severity;
    private String severityLabel;
    private LocalDateTime foundTime;

    /* ---- 正文（最新版本） ---- */
    private String sceneNarrative;
    private String handlingActions;
    private String evidenceNote;
    private String causeConclusion;
    private String correctiveAction;
    private Long ownerId;
    private String ownerName;
    private LocalDate dueDate;

    /* ---- 关联航线：事发快照 + 当前档案 ---- */
    private Long routeId;
    private String snapRouteCode;
    private String snapRouteName;
    private String snapRouteWindLevel;
    private java.math.BigDecimal snapRouteWindSpeed;
    private boolean currentRouteExists;
    private String currentRouteCode;
    private String currentRouteName;
    private String currentRouteWindLevel;
    private java.math.BigDecimal currentRouteWindSpeed;
    private Integer currentRouteStatus;
    private List<String> routeDiffFields;

    /* ---- 关联值守：事发快照 + 当前档案 ---- */
    private Long watchId;
    private LocalDateTime snapWatchTakeoff;
    private LocalDateTime snapWatchEnd;
    private Long snapWatchOperatorId;
    private String snapWatchOperatorName;
    private Long snapWatchReviewerId;
    private String snapWatchReviewerName;
    private boolean currentWatchExists;
    private String currentWatchStatus;
    private String currentWatchStatusLabel;
    private String currentWatchOperatorName;
    private String currentWatchReviewerName;
    private List<String> watchDiffFields;

    /* ---- 锚点快照（含当前对比） ---- */
    private List<IncidentAnchorViewDTO> anchorSnapshots;

    /* ---- 人员 ---- */
    private Long reporterId;
    private String reporterName;
    private List<Long> involvedStaffIds;
    private String involvedStaffNames;

    private Integer currentSealRound;

    /* ---- 修订历史（不可变，按 seq 升序） ---- */
    private List<IncidentRevisionViewDTO> revisions;

    /* ---- 封存轮次（可看出两次封存之间发生了什么） ---- */
    private List<IncidentSealRoundViewDTO> sealRounds;

    /* ---- 服务端按当前请求人计算的动作权限（仅用于按钮显隐，真正限制在服务端） ---- */
    private boolean canEditContent;
    private boolean canEnterInvestigating;
    private boolean canRequestSeal;
    private boolean canBackToInvestigating;
    private boolean canSeal;
    private boolean canReopen;
    private boolean sealed;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    private String policy;
    private String rejectedAlternative;
}
