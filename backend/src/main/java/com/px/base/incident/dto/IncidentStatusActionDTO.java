package com.px.base.incident.dto;

import lombok.Data;

/** 状态流转请求：必须带 expectedVersion；重新开启/退回调查等需写理由 */
@Data
public class IncidentStatusActionDTO {
    private Integer expectedVersion;
    private String reason;
}
