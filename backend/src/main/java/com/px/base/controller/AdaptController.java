package com.px.base.controller;

import com.px.base.dto.AdaptLogFilter;
import com.px.base.dto.AdaptLogPageDTO;
import com.px.base.dto.AdaptResultDTO;
import com.px.base.dto.BindDTO;
import com.px.base.dto.ResponseDTO;
import com.px.base.entity.RouteAnchor;
import com.px.base.service.AdaptLogExportService;
import com.px.base.service.AdaptLogSnapshotService;
import com.px.base.service.AdaptService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/adapt")
@RequiredArgsConstructor
public class AdaptController {
    private final AdaptService adaptService;
    private final AdaptLogSnapshotService adaptLogSnapshotService;
    private final AdaptLogExportService adaptLogExportService;

    @PostMapping("/bind")
    public ResponseDTO<AdaptResultDTO> bind(@RequestBody BindDTO dto) {
        try {
            AdaptResultDTO result = adaptService.bindAnchor(dto.getRouteId(), dto.getAnchorId());
            if (result.isValid()) {
                return ResponseDTO.success(result);
            } else {
                return ResponseDTO.error(400, result.getReason());
            }
        } catch (IllegalArgumentException e) {
            return ResponseDTO.error(400, e.getMessage());
        }
    }

    @PostMapping("/unbind")
    public ResponseDTO<AdaptResultDTO> unbind(@RequestBody BindDTO dto) {
        try {
            AdaptResultDTO result = adaptService.unbindAnchor(dto.getRouteId(), dto.getAnchorId());
            if (result.isValid()) {
                return ResponseDTO.success(result);
            } else {
                return ResponseDTO.error(400, result.getReason());
            }
        } catch (IllegalArgumentException e) {
            return ResponseDTO.error(400, e.getMessage());
        }
    }

    @GetMapping("/check")
    public ResponseDTO<AdaptResultDTO> check(
            @RequestParam Long routeId,
            @RequestParam Long anchorId) {
        try {
            AdaptResultDTO result = adaptService.checkAdapt(routeId, anchorId);
            return ResponseDTO.success(result);
        } catch (IllegalArgumentException e) {
            return ResponseDTO.error(400, e.getMessage());
        }
    }

    @PostMapping("/recheck/{routeId}")
    public ResponseDTO<AdaptResultDTO> recheck(@PathVariable Long routeId) {
        try {
            AdaptResultDTO result = adaptService.recheckRouteAnchors(routeId, null, null);
            return ResponseDTO.success(result);
        } catch (IllegalArgumentException e) {
            return ResponseDTO.error(400, e.getMessage());
        }
    }

    /**
     * 流水分页快照（读取的唯一口径）。
     *
     * @param routeId  筛选航线；不传为全部航线
     * @param pageNo   页码，从1开始
     * @param pageSize 每页条数
     *
     * 返回的 total / pageNo / records 与 snapshotTime/snapshotMaxId 对应同一个查询时刻；
     * 服务端固定按 create_time DESC, id DESC 排序，导出与本接口共用该排序。
     */
    @GetMapping("/logs")
    public ResponseDTO<AdaptLogPageDTO> getLogs(
            @RequestParam(required = false) Long routeId,
            @RequestParam(required = false, defaultValue = "1") int pageNo,
            @RequestParam(required = false, defaultValue = "20") int pageSize) {
        AdaptLogFilter filter = AdaptLogFilter.builder().routeId(routeId).build();
        return ResponseDTO.success(adaptLogSnapshotService.page(filter, pageNo, pageSize));
    }

    /**
     * 导出流水快照。必须带当前筛选条件（routeId），服务端冻结快照并生成排序后的文件，
     * 前端不再拿旧数组拼 JSON。查询/导出期间新增的流水不会混入，文件内 total 与行数一致；
     * 空命中同样正常返回文件（内容 resultState=NO_MATCH、total=0），与请求失败（HTTP 4xx/5xx）明确区分。
     */
    @GetMapping("/logs/export")
    public ResponseEntity<byte[]> exportLogs(@RequestParam(required = false) Long routeId) {
        AdaptLogFilter filter = AdaptLogFilter.builder().routeId(routeId).build();
        AdaptLogExportService.ExportFile file = adaptLogExportService.export(filter);

        String encoded = URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        // filename* 支持中文/UTF-8；filename 给老浏览器一个 ASCII 兜底
        String disposition = "attachment; filename=\""
                + file.fileName().replaceAll("[^A-Za-z0-9._-]", "_")
                + "\"; filename*=UTF-8''" + encoded;

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.parseMediaType("application/json;charset=UTF-8"))
                .contentLength(file.size())
                .body(file.content());
    }

    @GetMapping("/bound/{routeId}")
    public ResponseDTO<List<RouteAnchor>> getBoundAnchors(@PathVariable Long routeId) {
        List<RouteAnchor> anchors = adaptService.getBoundAnchors(routeId);
        return ResponseDTO.success(anchors);
    }
}
