package com.px.base.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 事件涉及锚点的不可变快照：建档时写入，之后锚点改名、停用、调整承重/风区都不影响。
 * anchorId 保留实时跳转能力（查看当前资料对比差异），但展示一律以快照字段为准。
 */
@Entity
@Table(name = "incident_anchor_snapshot")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentAnchorSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_id", nullable = false)
    private Long incidentId;

    @Column(name = "anchor_id", nullable = false)
    private Long anchorId;

    @Column(name = "anchor_code", nullable = false, length = 50)
    private String anchorCode;

    @Column(name = "location_desc", length = 200)
    private String locationDesc;

    @Column(name = "anchor_zone", length = 50)
    private String anchorZone;

    /** 事发时锚点状态：0 停用 / 1 启用 */
    @Column(name = "anchor_status")
    private Integer anchorStatus;

    @Column(name = "max_weight", precision = 10, scale = 2)
    private BigDecimal maxWeight;

    @Column(name = "min_wind_speed", precision = 5, scale = 2)
    private BigDecimal minWindSpeed;

    @Column(name = "max_wind_speed", precision = 5, scale = 2)
    private BigDecimal maxWindSpeed;

    @Column(name = "sort_no")
    @Builder.Default
    private Integer sortNo = 0;

    @Column(name = "create_time", updatable = false)
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
    }
}
