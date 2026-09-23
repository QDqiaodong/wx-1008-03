package com.px.base.rule;

import com.px.base.entity.GroundCert;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资质判定核心规则验收：
 *  风级+区域单证覆盖、部分区域逐项列出、跨午夜只按起飞时刻且结论稳定、
 *  吊销终态、多证不能拼凑、待生效/过期判定、边界日含端点。
 */
class QualificationEvaluatorTest {

    private GroundCert cert(String no, String winds, String zones,
                            LocalDate from, LocalDate to, boolean revoked) {
        return GroundCert.builder()
                .id(1L)
                .certNo(no)
                .staffId(10L)
                .windLevels(winds)
                .anchorZones(zones)
                .effectiveDate(from)
                .expiryDate(to)
                .revoked(revoked ? 1 : 0)
                .build();
    }

    private final LocalDate d2026_09_20 = LocalDate.of(2026, 9, 20);
    private final LocalDate d2026_09_23 = LocalDate.of(2026, 9, 23);
    private final LocalDate d2026_09_24 = LocalDate.of(2026, 9, 24);

    @Test
    void 单张证书同时覆盖风级与全部区域才合格() {
        GroundCert c = cert("C1", "强风,疾风", "东区,西区", d2026_09_20, d2026_09_24, false);

        QualificationResult ok = QualificationEvaluator.evaluate(
                10L, "赵", List.of(c), "强风", Set.of("东区", "西区"),
                d2026_09_23.atTime(9, 0));

        assertThat(ok.qualified()).isTrue();
        assertThat(ok.activeCertStatus()).isEqualTo("VALID");
        assertThat(ok.detailMessages()).isEmpty();
    }

    @Test
    void 证书只覆盖部分锚点区域时逐项列出未覆盖区域() {
        GroundCert c = cert("C1", "强风,疾风", "东区", d2026_09_20, d2026_09_24, false);

        QualificationResult r = QualificationEvaluator.evaluate(
                10L, "赵", List.of(c), "强风", Set.of("东区", "西区", "南区"),
                d2026_09_23.atTime(9, 0));

        assertThat(r.qualified()).isFalse();
        assertThat(r.missingZones()).containsExactlyInAnyOrder("西区", "南区");
        assertThat(r.detailMessages()).hasSize(1);
        assertThat(r.detailMessages().get(0)).contains("西区", "南区");
    }

    @Test
    void 风级不覆盖时明确指出缺哪个风级() {
        GroundCert c = cert("C1", "微风,轻风", "东区", d2026_09_20, d2026_09_24, false);

        QualificationResult r = QualificationEvaluator.evaluate(
                10L, "钱", List.of(c), "强风", Set.of("东区"), d2026_09_23.atTime(9, 0));

        assertThat(r.qualified()).isFalse();
        assertThat(r.missingWindLevels()).containsExactly("强风");
        assertThat(r.detailMessages().get(0)).contains("强风");
    }

    @Test
    void 多张证书不能拼凑风级与区域() {
        // A 证有风级无区域，B 证有区域无风级——任何单张都不完整，拼凑不允许
        GroundCert a = cert("A", "强风", "东区", d2026_09_20, d2026_09_24, false);
        GroundCert b = cert("B", "微风", "东区,西区", d2026_09_20, d2026_09_24, false);

        QualificationResult r = QualificationEvaluator.evaluate(
                10L, "钱", List.of(a, b), "强风", Set.of("东区", "西区"),
                d2026_09_23.atTime(9, 0));

        assertThat(r.qualified()).isFalse();
        // 选缺失最少的 A 作为代表：缺“西区”一个区域
        assertThat(r.activeCertNo()).isEqualTo("A");
        assertThat(r.missingZones()).containsExactly("西区");
    }

    @Test
    void 跨午夜任务按起飞时刻判定_起飞日有效即合格_与结束时刻无关() {
        // 证书 9/20~9/23 有效；任务 9/23 23:30 起飞、9/24 01:00 结束（跨午夜）
        GroundCert c = cert("C1", "强风", "西区", d2026_09_20, d2026_09_23, false);

        LocalDateTime takeoff = d2026_09_23.atTime(23, 30);
        QualificationResult r = QualificationEvaluator.evaluate(
                10L, "赵", List.of(c), "强风", Set.of("西区"), takeoff);

        assertThat(r.qualified()).isTrue();
        assertThat(r.activeCertStatus()).isEqualTo("VALID");
        // 把结束时刻从 01:00 改成 05:00 不影响结论（评估器根本不接收结束时刻）
        assertThat(c.validAtTakeoff(takeoff)).isTrue();
    }

    @Test
    void 跨午夜起飞落在到期次日即无效_结论只由起飞时刻决定() {
        GroundCert c = cert("C1", "强风", "西区", d2026_09_20, d2026_09_23, false);

        QualificationResult r = QualificationEvaluator.evaluate(
                10L, "赵", List.of(c), "强风", Set.of("西区"),
                d2026_09_24.atTime(0, 30));

        assertThat(r.qualified()).isFalse();
        assertThat(r.activeCertStatus()).isEqualTo("EXPIRED");
        assertThat(r.detailMessages().get(0)).contains("已过期");
    }

    @Test
    void 生效日与到期日按自然日含端点() {
        GroundCert c = cert("C1", "强风", "西区", d2026_09_23, d2026_09_23, false);
        assertThat(c.validAtTakeoff(d2026_09_23.atTime(0, 0))).isTrue();
        assertThat(c.validAtTakeoff(d2026_09_23.atTime(23, 59))).isTrue();
        assertThat(c.validAtTakeoff(d2026_09_20.atTime(12, 0))).isFalse();
        assertThat(c.validAtTakeoff(d2026_09_24.atTime(0, 0))).isFalse();
    }

    @Test
    void 吊销证书在任何飞行日都无效_改飞行日不能复活() {
        GroundCert c = cert("C-REVOKED", "强风,疾风", "东区,西区",
                LocalDate.of(2020, 1, 1), LocalDate.of(2030, 12, 31), true);

        for (LocalDateTime takeoff : List.of(
                d2026_09_23.atTime(9, 0),
                d2026_09_24.atTime(9, 0),
                LocalDateTime.of(2030, 1, 1, 9, 0))) {
            QualificationResult r = QualificationEvaluator.evaluate(
                    10L, "钱", List.of(c), "强风", Set.of("西区"), takeoff);
            assertThat(r.qualified()).as("起飞 %s 必须仍因吊销不合格", takeoff).isFalse();
            assertThat(r.activeCertStatus()).isEqualTo("REVOKED");
            assertThat(r.detailMessages().get(0)).contains("吊销", "改飞行日");
        }
    }

    @Test
    void 吊销证与有效证并存时选有效证_吊销证不影响当班() {
        GroundCert revoked = cert("C-OLD", "强风,疾风", "东区,西区",
                LocalDate.of(2020, 1, 1), LocalDate.of(2030, 12, 31), true);
        GroundCert valid = cert("C-NEW", "强风", "西区", d2026_09_20, d2026_09_24, false);

        QualificationResult r = QualificationEvaluator.evaluate(
                10L, "赵", List.of(revoked, valid), "强风", Set.of("西区"),
                d2026_09_23.atTime(9, 0));

        assertThat(r.qualified()).isTrue();
        assertThat(r.activeCertNo()).isEqualTo("C-NEW");
    }

    @Test
    void 待生效证书在起飞日之前判为待生效() {
        GroundCert c = cert("C-FUTURE", "强风", "西区", d2026_09_24, LocalDate.of(2026, 12, 31), false);

        QualificationResult r = QualificationEvaluator.evaluate(
                10L, "李", List.of(c), "强风", Set.of("西区"), d2026_09_23.atTime(9, 0));

        assertThat(r.qualified()).isFalse();
        assertThat(r.activeCertStatus()).isEqualTo("PENDING");
    }

    @Test
    void 无任何证书时给出明确结论() {
        QualificationResult r = QualificationEvaluator.evaluate(
                10L, "新人", List.of(), "强风", Set.of("西区"), d2026_09_23.atTime(9, 0));

        assertThat(r.qualified()).isFalse();
        assertThat(r.activeCertStatus()).isEqualTo("NONE");
        assertThat(r.detailMessages().get(0)).contains("没有任何资质证");
    }

    @Test
    void 证书状态随参考时刻在待生效有效过期间推导_吊销恒定() {
        GroundCert c = cert("C1", "强风", "西区", d2026_09_20, d2026_09_23, false);
        assertThat(c.deriveStatus(d2026_09_20.atTime(0, 0))).isEqualTo("VALID");
        assertThat(c.deriveStatus(LocalDate.of(2026, 9, 19).atTime(23, 59))).isEqualTo("PENDING");
        assertThat(c.deriveStatus(d2026_09_24.atTime(0, 0))).isEqualTo("EXPIRED");

        GroundCert revoked = cert("C2", "强风", "西区", LocalDate.of(2020, 1, 1),
                LocalDate.of(2030, 12, 31), true);
        assertThat(revoked.deriveStatus(d2026_09_23.atTime(9, 0))).isEqualTo("REVOKED");
    }
}
