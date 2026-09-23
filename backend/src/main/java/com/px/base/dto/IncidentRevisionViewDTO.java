package com.px.base.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一条不可变修订记录的视图。
 * before/after 为整份正文 JSON 原文（与 IncidentContentDTO 同构），
 * 前端解析后即可并排展示任意两版差异；服务端另给 changedFields 便于直接高亮。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentRevisionViewDTO {
    private Long id;
    private Integer seq;
    private String revisionType;
    private String revisionTypeLabel;
    private String fromStatus;
    private String fromStatusLabel;
    private String toStatus;
    private String toStatusLabel;
    private Long operatorId;
    private String operatorName;
    private String operatorRole;
    private String operatorRoleLabel;
    private String reason;
    private String beforeContent;
    private String afterContent;
    /** 该条修订真正改动的正文字段中文名（状态流转但正文不变时为空） */
    private java.util.List<String> changedFields;
    private Integer sealRound;
    private LocalDateTime operateTime;
}
