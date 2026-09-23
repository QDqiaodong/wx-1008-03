package com.px.base.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 航线入口条目：航线基本信息 + 该航线全部在用锚点区域 + 某日值守摘要。
 * 资质结论与人员列表、值守详情同源（同一评估器、同一起飞时刻口径）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteWatchEntryDTO {
    private Long routeId;
    private String routeCode;
    private String routeName;
    private String windLevel;
    private BigDecimal windSpeed;
    private Integer status;
    private List<String> activeAnchorCodes;
    private List<String> requiredZones;

    private Long watchId;
    private String watchStatus;
    private String watchStatusLabel;
    private LocalDate flightDate;

    private Long operatorId;
    private String operatorName;
    private Long reviewerId;
    private String reviewerName;
    private boolean operatorQualified;
    private boolean reviewerQualified;
    private boolean distinctPeople;
    private Integer operatorArrived;
    private boolean ready;
    private List<String> gapMessages;

    private String policy;
}
