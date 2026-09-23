package com.px.base.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 正文修订提交（DRAFT 补充 / INVESTIGATING 修订 / REOPENED 封存后更正）。
 *
 * @param expectedVersion 打开编辑表单时看到的版本号——整份事件版本冲突口径的唯一判据。
 *                        与服务端当前版本不一致即整体拒绝（409），返回冲突字段与最新事件。
 * @param reason          修订理由；REOPENED 态更正必填（封存后的每次更正都必须带理由）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentEditDTO {
    private Long expectedVersion;
    private String reason;
    private IncidentContentDTO content;
}
