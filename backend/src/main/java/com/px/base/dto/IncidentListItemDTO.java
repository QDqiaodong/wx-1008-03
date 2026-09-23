package com.px.base.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 事件列表项。普通值班员只能看到自己报告或参与的事件（服务端过滤，非仅前端隐藏）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentListItemDTO {
    private Long id;
    private String incidentCode;
    private String title;
    private String status;
    private String statusLabel;
    private Long version;
    private String severity;
    private String severityLabel;
    private LocalDateTime foundTime;

    private String snapRouteCode;
    private String snapRouteName;
    private String snapRouteWindLevel;
    private String reporterName;
    private String involvedStaffNames;

    private Integer currentSealRound;
    private Integer revisionCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
