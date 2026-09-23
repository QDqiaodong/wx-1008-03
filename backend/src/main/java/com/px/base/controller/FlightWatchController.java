package com.px.base.controller;

import com.px.base.dto.ResponseDTO;
import com.px.base.dto.RouteWatchEntryDTO;
import com.px.base.dto.WatchActionDTO;
import com.px.base.dto.WatchUpsertDTO;
import com.px.base.dto.WatchViewDTO;
import com.px.base.entity.FlightWatch;
import com.px.base.service.FlightWatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/watch")
@RequiredArgsConstructor
public class FlightWatchController {

    private final FlightWatchService watchService;

    /** 值守列表，可按航线过滤 */
    @GetMapping
    public ResponseDTO<List<WatchViewDTO>> list(@RequestParam(required = false) Long routeId) {
        return ResponseDTO.success(watchService.listViews(routeId));
    }

    @GetMapping("/{id}")
    public ResponseDTO<WatchViewDTO> get(@PathVariable Long id) {
        return ResponseDTO.success(watchService.getView(id));
    }

    /**
     * 航线入口：按日期（默认今天）汇总每条航线的当日值守与资格结论。
     * 传入的是该日的预计起飞时刻（含时分），跨午夜任务按该时刻判定。
     */
    @GetMapping("/route-entries")
    public ResponseDTO<List<RouteWatchEntryDTO>> routeEntries(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime takeoff) {
        return ResponseDTO.success(watchService.routeEntries(takeoff));
    }

    @PostMapping
    public ResponseDTO<FlightWatch> create(@RequestBody WatchUpsertDTO dto) {
        return ResponseDTO.success(watchService.create(dto));
    }

    @PutMapping("/{id}")
    public ResponseDTO<FlightWatch> update(@PathVariable Long id, @RequestBody WatchUpsertDTO dto) {
        return ResponseDTO.success(watchService.update(id, dto));
    }

    /** 操作员确认现场到位 */
    @PostMapping("/{id}/operator-arrive")
    public ResponseDTO<FlightWatch> operatorArrive(@PathVariable Long id) {
        return ResponseDTO.success(watchService.operatorArrive(id));
    }

    /** 操作员（或主管）撤回到位，回到草拟 */
    @PostMapping("/{id}/operator-withdraw")
    public ResponseDTO<FlightWatch> operatorWithdraw(@PathVariable Long id) {
        return ResponseDTO.success(watchService.operatorWithdraw(id));
    }

    /** 复核员确认就绪（操作员到位 + 实时资质均覆盖才放行） */
    @PostMapping("/{id}/ready")
    public ResponseDTO<FlightWatch> ready(@PathVariable Long id) {
        return ResponseDTO.success(watchService.reviewerReady(id));
    }

    /** 取消值守（已就绪仅主管） */
    @PostMapping("/{id}/cancel")
    public ResponseDTO<FlightWatch> cancel(@PathVariable Long id,
                                           @RequestBody(required = false) WatchActionDTO body) {
        return ResponseDTO.success(watchService.cancel(id, body == null ? null : body.getReason()));
    }
}
