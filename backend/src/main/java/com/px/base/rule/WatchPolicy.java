package com.px.base.rule;

import java.util.List;

/**
 * 全系统统一常量：风级口径、跨午夜判定口径说明。
 * 人员列表、值守详情、航线入口三处页面共用 {@link #TAKEOFF_POLICY} 文案。
 */
public final class WatchPolicy {

    private WatchPolicy() {
    }

    /** 风级按既有航线口径从低到高，证书多选授权 */
    public static final List<String> WIND_LEVELS =
            List.of("微风", "轻风", "和风", "强风", "疾风");

    /**
     * 已拍板口径：按【预计起飞时刻】判定。
     */
    public static final String TAKEOFF_POLICY =
            "跨午夜任务统一按【预计起飞时刻】所在自然日判定证书有效性：起飞当日落在证书生效日至到期日内即视为有效，"
                    + "不要求证书覆盖整个预计飞行区间。人员列表、值守详情、航线入口三处均使用该口径。";

    /**
     * 为什么不采用“覆盖整个预计飞行区间”口径——三处页面同文展示。
     */
    public static final String REJECTED_POLICY_REASON =
            "不采用“证书覆盖整个预计飞行区间”的原因：动力伞跨夜飞行的实际结束时刻受风况影响很大，"
                    + "计划结束时间在现场经常顺延，按区间判定会让一份在起飞时合法的证书仅因计划延迟就被判定无效，"
                    + "结论不稳定且不可复核；起飞时刻是排班、放行、审计中唯一确定且可追溯的锚点，故全系统只认起飞时刻。";
}
