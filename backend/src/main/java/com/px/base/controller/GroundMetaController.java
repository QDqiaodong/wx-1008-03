package com.px.base.controller;

import com.px.base.dto.ResponseDTO;
import com.px.base.rule.WatchPolicy;
import com.px.base.service.AnchorZoneService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 锚点区域 + 可选风级 + 跨午夜口径，前端表单/说明统一从这里取 */
@RestController
@RequestMapping("/api/ground/meta")
@RequiredArgsConstructor
public class GroundMetaController {

    private final AnchorZoneService anchorZoneService;

    @GetMapping
    public ResponseDTO<Map<String, Object>> meta() {
        return ResponseDTO.success(Map.of(
                "windLevels", WatchPolicy.WIND_LEVELS,
                "anchorZones", anchorZoneService.findAllZones(),
                "policy", WatchPolicy.TAKEOFF_POLICY,
                "rejectedReason", WatchPolicy.REJECTED_POLICY_REASON
        ));
    }

    @GetMapping("/zones")
    public ResponseDTO<List<String>> zones() {
        return ResponseDTO.success(anchorZoneService.findAllZones());
    }
}
