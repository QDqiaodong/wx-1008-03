package com.px.base.incident.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 事件关联锚点快照。建事件时从锚点档案冻结编号/区域/位置/承重/状态，
 * 之后锚点档案修改、停用都不影响事件显示；anchor_id 保留用于跳转当前资料对比差异。
 * 快照行一经创建永不修改、删除。
 */
@Entity
@Table(name = "incident_anchor")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentAnchor {

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

    /** 事发时锚点状态中文口径：启用/停用（冻结，不随后续启停变化） */
    @Column(name = "status_snapshot", nullable = false, length = 20)
    private String statusSnapshot;

    @Column(name = "max_weight", precision = 10, scale = 2)
    private BigDecimal maxWeight;

    @Column(name = "create_time", updatable = false)
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
    }
}
