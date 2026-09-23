package com.px.base.incident.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/** 锚点：左侧事发快照（冻结），右侧当前资料（实时，供对比差异/跳转） */
@Data
@Builder
public class IncidentAnchorViewDTO {
    /* 事发快照（永不变） */
    private Long anchorId;
    private String anchorCode;
    private String locationDesc;
    private String anchorZone;
    private String statusSnapshot;
    private BigDecimal maxWeight;

    /* 当前资料（可能为 null：锚点已被删除） */
    private Boolean currentExists;
    private String currentLocationDesc;
    private String currentAnchorZone;
    private String currentStatus;
    private BigDecimal currentMaxWeight;
    /** 快照与当前是否存在差异 */
    private Boolean changed;
}
