package com.px.base.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 适配流水快照的筛选条件。读取与导出共用同一入参，
 * 保证页面看到的列表与导出文件是同一条查询口径。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdaptLogFilter {
    /** 筛选航线ID；null 表示全部航线 */
    private Long routeId;
}
