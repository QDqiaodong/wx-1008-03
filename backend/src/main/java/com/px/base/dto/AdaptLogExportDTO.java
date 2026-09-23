package com.px.base.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 适配流水导出快照的元信息与筛选条件，随文件内容一起落盘，可离线复核。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdaptLogExportDTO {
    /** 导出文件格式版本，便于以后核对 */
    private String formatVersion;
    /** OK-有命中；NO_MATCH-筛选有效但没有任何命中（与请求失败明确区分） */
    private String resultState;
    private Meta meta;
    private FilterInfo filter;
    private java.util.List<com.px.base.entity.AdaptLog> records;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Meta {
        /** 服务端实际生成文件的时刻 */
        private String exportedAt;
        /** 冻结快照的查询时刻（读取与导出共用同一口径） */
        private String snapshotTime;
        /** 快照高水位自增ID */
        private Long snapshotMaxId;
        /** 快照内记录总数，必须等于 records 行数 */
        private long total;
        /** 服务端固定排序 */
        private String sort;
        private String note;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FilterInfo {
        /** 筛选航线ID；全部航线时为 null */
        private Long routeId;
        /** 筛选航线编号；全部航线时为“全部航线” */
        private String routeCode;
        /** 筛选描述，人类可读 */
        private String description;
    }
}
