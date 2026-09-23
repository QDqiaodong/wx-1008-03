package com.px.base.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 资质证视图。status 永远按参考时刻（人员列表默认今天/指定起飞时刻）实时推导，
 * 不存库，避免“到期后状态不刷新”。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CertViewDTO {
    private Long id;
    private String certNo;
    private Long staffId;
    private String staffName;
    private List<String> windLevels;
    private List<String> anchorZones;
    private LocalDate effectiveDate;
    private LocalDate expiryDate;
    /** PENDING/VALID/EXPIRED/REVOKED（按 referenceTime 推导） */
    private String status;
    private LocalDateTime referenceTime;
    private LocalDateTime revokeTime;
    private String revokeReason;
    private String revokedByName;
}
