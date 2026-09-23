package com.px.base.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 资质结论的序列化视图（对应 rule.QualificationResult）。
 * detailMessages 逐项指出“哪名人员缺哪一段资格”。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualificationView {
    private Long staffId;
    private String staffName;
    private boolean qualified;
    private String activeCertNo;
    /** VALID/PENDING/EXPIRED/REVOKED/NONE */
    private String activeCertStatus;
    private List<String> coveredWindLevels;
    private List<String> coveredZones;
    private List<String> missingWindLevels;
    private List<String> missingZones;
    private List<String> detailMessages;
}
