package com.px.base.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 地勤资质证。
 *
 * 一张证书同时写明：适用风级（CSV，见 QualificationEvaluator.WIND_LEVELS）、
 * 可负责的锚点区域（CSV）、生效日、到期日。
 *
 * 吊销是终态：revoked=1 的证书在任何飞行日都无效，改飞行日不能复活。
 * 待生效/有效/已过期三态按判定时刻（跨午夜任务统一取起飞时刻）的日期推导，
 * 不落库、不走定时器，保证任意时刻重新打开页面结论都一致。
 */
@Entity
@Table(name = "ground_cert")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroundCert {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_VALID = "VALID";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_REVOKED = "REVOKED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cert_no", unique = true, nullable = false, length = 50)
    private String certNo;

    @Column(name = "staff_id", nullable = false)
    private Long staffId;

    /** 适用风级，逗号分隔，如“强风,疾风” */
    @Column(name = "wind_levels", nullable = false, length = 200)
    private String windLevels;

    /** 可负责的锚点区域，逗号分隔，如“东区,西区” */
    @Column(name = "anchor_zones", nullable = false, length = 500)
    private String anchorZones;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Column(name = "revoked")
    @Builder.Default
    private Integer revoked = 0;

    @Column(name = "revoke_time")
    private LocalDateTime revokeTime;

    @Column(name = "revoke_reason", length = 300)
    private String revokeReason;

    @Column(name = "revoked_by_id")
    private Long revokedById;

    @Column(name = "revoked_by_name", length = 50)
    private String revokedByName;

    @Column(name = "create_time", updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }

    public boolean isRevoked() {
        return revoked != null && revoked == 1;
    }

    /**
     * 按给定时刻所在自然日推导证书状态。跨午夜任务调用方必须传“起飞时刻”，
     * 全系统（人员列表 / 值守详情 / 航线入口）走同一个口径。
     */
    public String deriveStatus(LocalDateTime referenceTime) {
        if (isRevoked()) {
            return STATUS_REVOKED;
        }
        LocalDate day = referenceTime.toLocalDate();
        if (day.isBefore(effectiveDate)) {
            return STATUS_PENDING;
        }
        if (day.isAfter(expiryDate)) {
            return STATUS_EXPIRED;
        }
        return STATUS_VALID;
    }

    /** 证书在某起飞时刻是否有效：未吊销，且起飞当日位于 [生效日, 到期日]。 */
    public boolean validAtTakeoff(LocalDateTime takeoffTime) {
        if (isRevoked() || takeoffTime == null) {
            return false;
        }
        LocalDate day = takeoffTime.toLocalDate();
        return !day.isBefore(effectiveDate) && !day.isAfter(expiryDate);
    }
}
