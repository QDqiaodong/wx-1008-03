package com.px.base.incident.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 事件正文修订请求（调查中编辑 / 封存后更正共用）。
 * 必须携带 expectedVersion：客户端打开详情时看到的版本号。
 * 整份事件版本冲突口径：服务端比对当前 version，不一致返回 409 + 冲突字段 + 最新版本。
 */
@Data
public class IncidentEditDTO {
    private Integer expectedVersion;
    private String title;
    private LocalDateTime foundTime;
    private String severity;
    private String incidentNote;
    private String handlingAction;
    private String evidenceDesc;
    private String rootCause;
    private String correctiveAction;
    private Long ownerId;
    private LocalDate dueDate;
    /** 修订/更正理由：封存后的 CORRECTION 必填 */
    private String changeReason;
}
