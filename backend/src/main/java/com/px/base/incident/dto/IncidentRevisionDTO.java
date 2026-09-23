package com.px.base.incident.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 一条不可变修订（修订记录页用）。正文为该版本完整内容；
 * 与上一版的字段差异由服务端直接标出（changedFields），前端并排展示新旧两版。
 */
@Data
@Builder
public class IncidentRevisionDTO {
    private Long id;
    private Integer revisionNo;
    private String changeType;
    private String changeTypeLabel;
    private String changeReason;

    private String title;
    private LocalDateTime foundTime;
    private String severity;
    private String severityLabel;
    private String incidentNote;
    private String handlingAction;
    private String evidenceDesc;
    private String rootCause;
    private String correctiveAction;
    private Long ownerId;
    private String ownerName;
    private LocalDate dueDate;

    private String statusBefore;
    private String statusBeforeLabel;
    private String statusAfter;
    private String statusAfterLabel;

    private Long operatorId;
    private String operatorName;
    private LocalDateTime createTime;

    /** 与上一版相比发生变化的字段中文名（CREATE 版显示"初始建立"） */
    private java.util.List<String> changedFields;
}
