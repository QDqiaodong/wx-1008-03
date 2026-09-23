package com.px.base.controller;

import com.px.base.dto.CertViewDTO;
import com.px.base.dto.GroundCertDTO;
import com.px.base.dto.ResponseDTO;
import com.px.base.entity.GroundCert;
import com.px.base.entity.GroundStaff;
import com.px.base.service.GroundCertService;
import com.px.base.service.GroundStaffService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ground/cert")
@RequiredArgsConstructor
public class GroundCertController {

    private final GroundCertService certService;
    private final GroundStaffService staffService;

    /** 某人的全部证书（原始数据） */
    @GetMapping("/staff/{staffId}")
    public ResponseDTO<List<GroundCert>> listByStaff(@PathVariable Long staffId) {
        return ResponseDTO.success(certService.findByStaff(staffId));
    }

    /** 某人的证书视图（状态按起飞时刻推导），不传 takeoff 则按当前时刻 */
    @GetMapping("/staff/{staffId}/view")
    public ResponseDTO<List<CertViewDTO>> viewByStaff(
            @PathVariable Long staffId,
            @RequestParam(required = false)
            @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            java.time.LocalDateTime takeoff) {
        GroundStaff staff = staffService.getById(staffId);
        java.time.LocalDateTime ref = takeoff != null ? takeoff : java.time.LocalDateTime.now();
        List<CertViewDTO> views = certService.findByStaff(staffId).stream()
                .map(c -> certService.toView(c, staff, ref))
                .toList();
        return ResponseDTO.success(views);
    }

    @PostMapping
    public ResponseDTO<GroundCert> create(@RequestBody GroundCertDTO dto) {
        return ResponseDTO.success(certService.create(dto));
    }

    /** 吊销：服务端强制安全主管身份，返回被打回草拟的值守数量 */
    @PostMapping("/{id}/revoke")
    public ResponseDTO<java.util.Map<String, Integer>> revoke(
            @PathVariable Long id,
            @RequestBody(required = false) com.px.base.dto.WatchActionDTO body) {
        int affected = certService.revoke(id, body == null ? null : body.getReason());
        return ResponseDTO.success(java.util.Map.of("requalifiedWatches", affected));
    }
}
