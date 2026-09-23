package com.px.base.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 整份事件版本冲突（409）的结构化响应体。
 *
 * 冲突字段的口径是【整份】而非按字段合并：
 * 只要你提交基准版本之后事件又产生了新版本（任何修订或状态流转），
 * 就把“你提交的内容”与“服务端最新版本”逐字段比对，列出值不同的字段；
 * 你的提交不会落库任何一部分。由操作者查看 latest 后决定放弃或基于最新内容重新编辑。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentConflictDTO {
    private Long incidentId;
    private String incidentCode;

    /** 你编辑时所基于的版本 */
    private Long expectedVersion;
    /** 服务端当前最新版本（重新编辑时应以此为基准） */
    private Long latestVersion;
    /** 最新状态（可能状态已被别人推进，如别人已封存） */
    private String latestStatus;
    private String latestStatusLabel;

    /** 值不一致的正文/状态字段名（中文展示名） */
    private List<String> conflictFields;

    /** 最新事件完整详情（与 GET /api/incident/{id} 同构），免去前端再请求一次 */
    private IncidentViewDTO latest;

    public static final String FIELD_TITLE = "标题";
    public static final String FIELD_SEVERITY = "严重级别";
    public static final String FIELD_FOUND_TIME = "发现时间";
    public static final String FIELD_SCENE = "现场经过";
    public static final String FIELD_ACTIONS = "处置动作";
    public static final String FIELD_EVIDENCE = "证据说明";
    public static final String FIELD_CAUSE = "原因结论";
    public static final String FIELD_CORRECTIVE = "纠正措施";
    public static final String FIELD_OWNER = "负责人";
    public static final String FIELD_DUE = "整改期限";
    public static final String FIELD_STATUS = "事件状态";
}
