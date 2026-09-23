package com.px.base.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.px.base.dto.AdaptLogExportDTO;
import com.px.base.dto.AdaptLogFilter;
import com.px.base.dto.AdaptLogPageDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 适配流水导出：在服务端按当前筛选条件生成排序后的快照文件。
 *
 * 前端不允许拿旧数组拼 JSON——导出请求必须带当前筛选，
 * 服务端调用 AdaptLogSnapshotService 重新冻结快照并落盘，
 * 文件名与文件内容都带筛选航线、查询时刻、记录总数。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdaptLogExportService {

    public static final String STATE_OK = "OK";
    public static final String STATE_NO_MATCH = "NO_MATCH";
    private static final String FORMAT_VERSION = "adapt-logs-export/v1";

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter ISO_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final AdaptLogSnapshotService snapshotService;

    private final ObjectMapper exportMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public ExportFile export(AdaptLogFilter filter) {
        AdaptLogPageDTO snapshot = snapshotService.fullSnapshot(filter);

        String routeIdPart = filter != null && filter.getRouteId() != null
                ? String.valueOf(filter.getRouteId()) : null;
        String routeCode = snapshot.getRouteCode();
        String filterDescription = routeCode != null
                ? "筛选航线：" + routeCode
                : "筛选航线：全部航线";

        AdaptLogExportDTO body = AdaptLogExportDTO.builder()
                .formatVersion(FORMAT_VERSION)
                .resultState(snapshot.getTotal() > 0 ? STATE_OK : STATE_NO_MATCH)
                .meta(AdaptLogExportDTO.Meta.builder()
                        .exportedAt(java.time.LocalDateTime.now().format(ISO_TS))
                        .snapshotTime(snapshot.getSnapshotTime().format(ISO_TS))
                        .snapshotMaxId(snapshot.getSnapshotMaxId())
                        .total(snapshot.getTotal())
                        .sort(snapshot.getSort())
                        .note("本文件由服务端按当前筛选条件生成；total 与 records 行数为同一快照时刻的口径。"
                                + (snapshot.getTotal() == 0 ? "resultState=NO_MATCH 表示筛选有效但没有命中，并非请求失败。" : ""))
                        .build())
                .filter(AdaptLogExportDTO.FilterInfo.builder()
                        .routeId(filter != null ? filter.getRouteId() : null)
                        .routeCode(routeCode != null ? routeCode : "全部航线")
                        .description(filterDescription)
                        .build())
                .records(snapshot.getRecords())
                .build();

        byte[] content;
        try {
            content = exportMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new IllegalStateException("流水导出序列化失败: " + e.getMessage(), e);
        }

        String fileName = buildFileName(routeCode, routeIdPart,
                snapshot.getSnapshotTime().format(FILE_TS), snapshot.getTotal());
        return new ExportFile(fileName, content);
    }

    /**
     * 文件名包含筛选航线、查询时刻、记录总数：
     * adapt-logs_route-R-STRONG_20260923-153012_total-7.json
     * 全部航线时 route-ALL，空命中 total-0（与导出失败相区分，文件仍正常下载）。
     * 航线编号只保留文件名安全字符，其余退化为 route{id}。
     */
    private String buildFileName(String routeCode, String routeIdPart, String timestamp, long total) {
        String routePart;
        if (routeCode == null) {
            routePart = "route-ALL";
        } else {
            String safe = routeCode.replaceAll("[^A-Za-z0-9_-]", "");
            routePart = safe.isEmpty()
                    ? "route-" + (routeIdPart != null ? routeIdPart : "UNKNOWN")
                    : "route-" + safe.toUpperCase(Locale.ROOT);
        }
        return String.format("adapt-logs_%s_%s_total-%d.json", routePart, timestamp, total);
    }

    public record ExportFile(String fileName, byte[] content) {
        public int size() {
            return content.length;
        }

        public String contentUtf8() {
            return new String(content, StandardCharsets.UTF_8);
        }
    }
}
