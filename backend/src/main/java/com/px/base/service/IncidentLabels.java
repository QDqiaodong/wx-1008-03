package com.px.base.service;

import com.px.base.entity.IncidentEvent;
import com.px.base.entity.IncidentRevision;

/**
 * 事件领域的中文口径：状态、级别、修订类型标签集中管理，
 * 详情页/列表/修订记录/封存轮次共用同一套文案。
 */
public final class IncidentLabels {

    private IncidentLabels() {
    }

    public static String status(String s) {
        if (s == null) return "";
        return switch (s) {
            case IncidentEvent.STATUS_DRAFT -> "草稿";
            case IncidentEvent.STATUS_INVESTIGATING -> "调查中";
            case IncidentEvent.STATUS_PENDING_SEAL -> "待封存";
            case IncidentEvent.STATUS_SEALED -> "已封存";
            case IncidentEvent.STATUS_REOPENED -> "重新开启";
            default -> s;
        };
    }

    public static String severity(String s) {
        if (s == null) return "";
        return switch (s) {
            case IncidentEvent.SEVERITY_MINOR -> "一般";
            case IncidentEvent.SEVERITY_MAJOR -> "较大";
            case IncidentEvent.SEVERITY_CRITICAL -> "重大";
            default -> s;
        };
    }

    public static String revisionType(String t) {
        if (t == null) return "";
        return switch (t) {
            case IncidentRevision.TYPE_CREATE -> "建档";
            case IncidentRevision.TYPE_EDIT -> "正文修订";
            case IncidentRevision.TYPE_ENTER_INVESTIGATING -> "进入调查";
            case IncidentRevision.TYPE_REQUEST_SEAL -> "提交待封存";
            case IncidentRevision.TYPE_SEAL -> "封存";
            case IncidentRevision.TYPE_REOPEN -> "重新开启";
            case IncidentRevision.TYPE_CORRECT -> "封存后更正";
            default -> t;
        };
    }

    public static String role(String r) {
        if (r == null) return "";
        return switch (r) {
            case "SAFETY_OFFICER" -> "安全主管";
            case "STATION_OFFICER" -> "普通值班员";
            default -> r;
        };
    }

    public static String anchorStatus(Integer s) {
        if (s == null) return "未知";
        return s == 1 ? "启用" : "停用";
    }
}
