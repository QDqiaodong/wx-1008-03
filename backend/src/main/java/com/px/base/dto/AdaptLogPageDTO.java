package com.px.base.dto;

import com.px.base.entity.AdaptLog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 适配流水分页快照。
 *
 * 一次请求内先冻结快照高水位（最大自增ID），total / pageNo / records
 * 都来自该高水位对应的同一查询时刻；查询期间新写入的绑定/拒绝流水
 * 不会出现在本次快照里，因此 total 与记录行永远对得上。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdaptLogPageDTO {
    /** 本次快照对应的筛选条件 */
    private AdaptLogFilter filter;
    /** 筛选航线编号；全部航线时为 null */
    private String routeCode;
    /** 快照高水位（截至该自增ID，含） */
    private Long snapshotMaxId;
    /** 服务端冻结快照的时刻 */
    private LocalDateTime snapshotTime;
    /** 高水位下命中的总数（与本页 records 同口径，不是随手再查一次的实时值） */
    private long total;
    /** 当前页码，从1开始 */
    private int pageNo;
    /** 每页条数 */
    private int pageSize;
    /** 总页数 */
    private int totalPages;
    /** 稳定排序说明，读取与导出共用 */
    private String sort;
    private List<AdaptLog> records;
}
