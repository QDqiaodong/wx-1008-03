package com.px.base.controller;

import com.px.base.dto.GroundStaffDTO;
import com.px.base.dto.ResponseDTO;
import com.px.base.dto.StaffViewDTO;
import com.px.base.entity.GroundStaff;
import com.px.base.service.GroundStaffService;
import com.px.base.service.StaffViewService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/ground/staff")
@RequiredArgsConstructor
public class GroundStaffController {

    private final GroundStaffService staffService;
    private final StaffViewService staffViewService;

    /**
     * 人员列表。可带 routeId + takeoff 做胜任度试算；
     * takeoff 为预计起飞时刻（跨午夜按起飞时刻判定）。
     */
    @GetMapping
    public ResponseDTO<List<StaffViewDTO>> list(
            @RequestParam(required = false) Long routeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime takeoff) {
        return ResponseDTO.success(staffViewService.list(routeId, takeoff));
    }

    @GetMapping("/active")
    public ResponseDTO<List<GroundStaff>> active() {
        return ResponseDTO.success(staffService.findActive());
    }

    @PostMapping
    public ResponseDTO<GroundStaff> create(@RequestBody GroundStaffDTO dto) {
        return ResponseDTO.success(staffService.create(dto));
    }

    @PutMapping("/{id}")
    public ResponseDTO<GroundStaff> update(@PathVariable Long id, @RequestBody GroundStaffDTO dto) {
        return ResponseDTO.success(staffService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseDTO<Void> disable(@PathVariable Long id) {
        staffService.disable(id);
        return ResponseDTO.success(null);
    }

    /** 跨午夜判定口径（页面说明用） */
    @GetMapping("/policy")
    public ResponseDTO<java.util.Map<String, String>> policy() {
        return ResponseDTO.success(java.util.Map.of(
                "policy", staffViewService.policy(),
                "rejectedReason", staffViewService.rejectedPolicyReason()));
    }
}
