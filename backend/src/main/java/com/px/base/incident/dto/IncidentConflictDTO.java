package com.px.base.incident.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 整份事件版本冲突（HTTP 409）响应体 data：
 * 后到编辑拿着旧 expectedVersion 确认时，看到：
 *  - 冲突字段清单（各字段中文名 + 你提交的值 + 最新值）；
 *  - 服务端最新版本号与最新正文；
 * 再决定放弃，或基于最新内容重新编辑。绝不静默覆盖。
 */
@Data
@Builder
public class IncidentConflictDTO {
    private Long incidentId;
    private String incidentNo;
    /** 你编辑时所依据的版本 */
    private Integer expectedVersion;
    /** 服务端当前最新版本 */
    private Integer latestVersion;
    private String message;
    /** 发生冲突的字段（你的提交值与最新值都不同） */
    private List<ConflictField> conflictFields;
    /** 最新事件详情（供前端直接刷新到最新口径再改） */
    private IncidentDetailDTO latest;

    @Data
    @Builder
    public static class ConflictField {
        private String field;
        private String fieldLabel;
        private String yourValue;
        private String latestValue;
        /** 该字段最新一次是谁、什么时候、以什么修订类型改动的 */
        private String latestChangedBy;
        private String latestChangeType;
    }
}
