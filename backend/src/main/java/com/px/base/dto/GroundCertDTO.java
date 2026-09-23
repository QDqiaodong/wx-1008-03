package com.px.base.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroundCertDTO {
    private String certNo;
    private Long staffId;
    /** 适用风级（多选） */
    private List<String> windLevels;
    /** 可负责的锚点区域（多选） */
    private List<String> anchorZones;
    private LocalDate effectiveDate;
    private LocalDate expiryDate;
}
