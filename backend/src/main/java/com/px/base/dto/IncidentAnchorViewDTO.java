package com.px.base.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 事件涉及锚点：既有事发快照（冻结值），也附当前档案（实时值）供“查看差异”。
 * currentExists=false 表示锚点档案已被删除；快照内容仍完整可查。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentAnchorViewDTO {
    private Long anchorId;

    /* ---- 事发快照（历史，永不变化） ---- */
    private String anchorCode;
    private String locationDesc;
    private String anchorZone;
    private Integer anchorStatus;
    private String anchorStatusLabel;
    private BigDecimal maxWeight;
    private BigDecimal minWindSpeed;
    private BigDecimal maxWindSpeed;

    /* ---- 当前档案（实时跳转对比用；为空表示已删除） ---- */
    private boolean currentExists;
    private String currentAnchorCode;
    private String currentLocationDesc;
    private String currentAnchorZone;
    private Integer currentAnchorStatus;
    private String currentAnchorStatusLabel;
    private BigDecimal currentMaxWeight;
    private BigDecimal currentMinWindSpeed;
    private BigDecimal currentMaxWindSpeed;
    /** 快照与当前值存在差异的字段中文名（前端高亮） */
    private java.util.List<String> diffFields;
}
