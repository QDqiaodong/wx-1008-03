package com.px.base.rule;

import java.util.List;

/**
 * 单个人员的资质结论。人员列表、值守详情、航线入口三处都由
 * {@link QualificationEvaluator} 生成同构结论，保证同一天结论一致。
 *
 * @param staffId          人员ID
 * @param staffName        人员姓名（实时档案名；就绪后的历史另存快照名）
 * @param qualified        是否满足就绪要求：起飞时刻有效 + 单张证书覆盖风级与全部区域
 * @param activeCertNo     选中的“最佳”证书编号（覆盖缺失最少），用于就绪冻结快照
 * @param activeCertStatus 该最佳证书状态：VALID/PENDING/EXPIRED/REVOKED，无证书为 NONE
 * @param coveredWindLevels 最佳证书适用风级
 * @param coveredZones     最佳证书覆盖区域
 * @param missingWindLevels 缺失风级（至多一个：航线当前风级）
 * @param missingZones     未覆盖的锚点区域，逐项列出
 * @param detailMessages   逐项缺口说明（哪个人、缺哪段资格）
 */
public record QualificationResult(
        Long staffId,
        String staffName,
        boolean qualified,
        String activeCertNo,
        String activeCertStatus,
        List<String> coveredWindLevels,
        List<String> coveredZones,
        List<String> missingWindLevels,
        List<String> missingZones,
        List<String> detailMessages
) {
}
