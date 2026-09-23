package com.px.base.incident.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 建事件请求。watchId 可空（仅航线也可补录）；anchorIds 为涉及锚点。
 * 关联对象的关键名称/风级/人员/锚点状态在服务端建事件时冻结，前端传什么快照文本都不作数。
 */
@Data
public class IncidentCreateDTO {
    private String title;
    private Long watchId;
    private Long routeId;
    private List<Long> anchorIds;
    private LocalDateTime foundTime;
    /** MINOR / MAJOR / CRITICAL */
    private String severity;
    private String incidentNote;
    private String handlingAction;
    private String evidenceDesc;
}
