package com.px.base.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 状态流转请求（进入调查 / 提交待封存 / 封存 / 重新开启 / 退回调查）。
 * 所有流转都必须携带 expectedVersion；重新开启必须写清 basis（依据）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentTransitionDTO {
    private Long expectedVersion;
    /** 重新开启依据（REOPEN 必填）；进入调查/封存时也可填备注 */
    private String basis;
}
