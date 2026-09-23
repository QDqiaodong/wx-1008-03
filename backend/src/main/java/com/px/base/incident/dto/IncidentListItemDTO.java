package com.px.base.incident.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 列表行（普通值班员只看到自己参与/报告的，安全主管看全部，行级过滤在服务端） */
@Data
@Builder
public class IncidentListItemDTO {
    private Long id;
    private String incidentNo;
    private String title;
    private String routeCode;
    private String routeName;
    private String watchFlightDate;
    private LocalDateTime foundTime;
    private String severity;
    private String severityLabel;
    private String status;
    private String statusLabel;
    private Integer version;
    private Integer sealedCount;
    private String reporterName;
    private String ownerName;
    private LocalDateTime lastSealTime;
    private LocalDateTime createTime;
}
