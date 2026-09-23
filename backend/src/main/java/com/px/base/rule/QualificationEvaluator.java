package com.px.base.rule;

import com.px.base.entity.GroundCert;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 地勤资质判定引擎（纯逻辑，无 Spring 依赖，便于单元测试）。
 *
 * 口径（全系统唯一）：
 *  1) 跨午夜任务按【预计起飞时刻】判定证书有效：起飞当日 ∈ [生效日, 到期日] 且未吊销；
 *     不要求证书覆盖整个预计飞行区间。
 *  2) 一名人员必须用【同一张】有效证书同时覆盖：航线当前风级 + 该航线全部在用锚点区域。
 *     多张证书不能拼凑（现场必须可凭一张证核验）。
 *  3) 多张候选证书时选“覆盖缺失最少”的一张作为代表性证书（就绪快照也冻结它）；
 *     没有任何有效证书时，取一张状态最具体的证书说明是吊销/过期/待生效。
 */
public final class QualificationEvaluator {

    public static final String CERT_STATUS_NONE = "NONE";

    private QualificationEvaluator() {
    }

    /**
     * @param staffId      人员ID
     * @param staffName    人员姓名
     * @param certs        该人员全部证书（含吊销/过期，状态在内部按起飞时刻推导）
     * @param requiredWind 航线当前风级（单个）
     * @param requiredZones 航线全部在用锚点区域（去重）
     * @param takeoffTime  预计起飞时刻（跨午夜判定锚点）
     */
    public static QualificationResult evaluate(Long staffId, String staffName, List<GroundCert> certs,
                                               String requiredWind, Set<String> requiredZones,
                                               LocalDateTime takeoffTime) {
        Set<String> requiredWindSet = new HashSet<>();
        if (requiredWind != null && !requiredWind.isBlank()) {
            requiredWindSet.add(requiredWind.trim());
        }
        Set<String> zones = requiredZones == null ? Set.of() : new HashSet<>(requiredZones);

        if (certs == null || certs.isEmpty()) {
            return unqualified(staffId, staffName, null, CERT_STATUS_NONE,
                    List.of(), List.of(), List.copyOf(requiredWindSet), List.copyOf(zones),
                    List.of(String.format("人员「%s」名下没有任何资质证", staffName)));
        }

        // 第一步：在“起飞时刻有效”的证书里选覆盖缺失最少的
        GroundCert bestValid = null;
        int bestValidGap = Integer.MAX_VALUE;
        for (GroundCert cert : certs) {
            if (!cert.validAtTakeoff(takeoffTime)) {
                continue;
            }
            int gap = coverageGap(cert, requiredWindSet, zones);
            if (gap < bestValidGap) {
                bestValidGap = gap;
                bestValid = cert;
            }
        }

        if (bestValid != null) {
            return buildFromCert(staffId, staffName, bestValid, GroundCert.STATUS_VALID,
                    requiredWindSet, zones, takeoffTime);
        }

        // 第二步：没有任何有效证书——挑一张状态最“具体”的证书说明原因
        // 优先级：已吊销 > 已过期 > 待生效（吊销是永久硬伤，最应首先暴露）
        GroundCert representative = pickRepresentative(certs, takeoffTime);
        String status = representative.deriveStatus(takeoffTime);
        return buildFromCert(staffId, staffName, representative, status,
                requiredWindSet, zones, takeoffTime);
    }

    /** 覆盖缺失数：风级缺 + 区域缺，用于挑选最佳证书 */
    private static int coverageGap(GroundCert cert, Set<String> requiredWind, Set<String> requiredZones) {
        Set<String> winds = Csv.toSet(cert.getWindLevels());
        Set<String> zones = Csv.toSet(cert.getAnchorZones());
        int gap = 0;
        for (String w : requiredWind) {
            if (!winds.contains(w)) gap++;
        }
        for (String z : requiredZones) {
            if (!zones.contains(z)) gap++;
        }
        return gap;
    }

    private static GroundCert pickRepresentative(List<GroundCert> certs, LocalDateTime takeoffTime) {
        int bestRank = -1;
        GroundCert best = certs.get(0);
        for (GroundCert cert : certs) {
            int rank = switch (cert.deriveStatus(takeoffTime)) {
                case GroundCert.STATUS_REVOKED -> 3;
                case GroundCert.STATUS_EXPIRED -> 2;
                case GroundCert.STATUS_PENDING -> 1;
                default -> 0;
            };
            if (rank > bestRank) {
                bestRank = rank;
                best = cert;
            }
        }
        return best;
    }

    private static QualificationResult buildFromCert(Long staffId, String staffName, GroundCert cert,
                                                      String certStatus, Set<String> requiredWind,
                                                      Set<String> requiredZones,
                                                      LocalDateTime takeoffTime) {
        List<String> coveredWinds = Csv.split(cert.getWindLevels());
        List<String> coveredZones = Csv.split(cert.getAnchorZones());
        Set<String> coveredWindSet = new HashSet<>(coveredWinds);
        Set<String> coveredZoneSet = new HashSet<>(coveredZones);

        List<String> missingWinds = Csv.missing(coveredWindSet, requiredWind);
        List<String> missingZones = Csv.missing(coveredZoneSet, requiredZones);

        List<String> messages = new ArrayList<>();
        String scopeDesc = String.format("证书%s（适用风级[%s]、区域[%s]，%s~%s）",
                cert.getCertNo(),
                String.join("、", coveredWinds),
                coveredZones.isEmpty() ? "无" : String.join("、", coveredZones),
                cert.getEffectiveDate(), cert.getExpiryDate());

        switch (certStatus) {
            case GroundCert.STATUS_REVOKED -> messages.add(String.format(
                    "人员「%s」的%s已被吊销，吊销证书在任何飞行日都无效，改飞行日也不能恢复",
                    staffName, scopeDesc));
            case GroundCert.STATUS_EXPIRED -> messages.add(String.format(
                    "人员「%s」的%s按起飞时刻%tF判定已过期（到期日%s）",
                    staffName, scopeDesc, takeoffTime, cert.getExpiryDate()));
            case GroundCert.STATUS_PENDING -> messages.add(String.format(
                    "人员「%s」的%s按起飞时刻%tF判定尚未生效（生效日%s）",
                    staffName, scopeDesc, takeoffTime, cert.getEffectiveDate()));
            default -> {
                if (!missingWinds.isEmpty()) {
                    messages.add(String.format("人员「%s」的%s不适用航线当前风级：%s",
                            staffName, scopeDesc, String.join("、", missingWinds)));
                }
                if (!missingZones.isEmpty()) {
                    messages.add(String.format("人员「%s」的%s未覆盖在用锚点区域：%s",
                            staffName, scopeDesc, String.join("、", missingZones)));
                }
            }
        }

        boolean qualified = GroundCert.STATUS_VALID.equals(certStatus)
                && missingWinds.isEmpty()
                && missingZones.isEmpty();

        return new QualificationResult(
                staffId, staffName, qualified, cert.getCertNo(), certStatus,
                coveredWinds, coveredZones, missingWinds, missingZones, messages);
    }

    private static QualificationResult unqualified(Long staffId, String staffName, String certNo,
                                                    String certStatus, List<String> coveredWinds,
                                                    List<String> coveredZones, List<String> missingWinds,
                                                    List<String> missingZones, List<String> messages) {
        return new QualificationResult(staffId, staffName, false, certNo, certStatus,
                coveredWinds, coveredZones, missingWinds, missingZones, messages);
    }
}
