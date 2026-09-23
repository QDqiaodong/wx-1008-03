package com.px.base.incident.controller;

import com.px.base.dto.ResponseDTO;
import com.px.base.incident.dto.IncidentCreateDTO;
import com.px.base.incident.dto.IncidentDetailDTO;
import com.px.base.incident.dto.IncidentEditDTO;
import com.px.base.incident.dto.IncidentListItemDTO;
import com.px.base.incident.dto.IncidentRevisionDTO;
import com.px.base.incident.dto.IncidentStatusActionDTO;
import com.px.base.incident.service.IncidentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 飞行异常事件复盘接口。
 * 所有接口的权限与版本口径均在服务端强制：
 *  - 普通值班员列表/详情只能读到自己报告、负责或参与值守的事件；
 *  - 封存、重新开启仅安全主管；
 *  - 所有写操作必须携带 expectedVersion，旧版本确认返回 409 + 冲突字段 + 最新版本。
 */
@RestController
@RequestMapping("/api/incidents")
@RequiredArgsConstructor
public class IncidentController {

    private final IncidentService incidentService;

    /** 口径元数据：并发策略与“为何不采用按字段合并”的固定说明 */
    @GetMapping("/meta")
    public ResponseDTO<Map<String, String>> meta() {
        return ResponseDTO.success(Map.of(
                "concurrencyPolicy", IncidentService.CONCURRENCY_POLICY,
                "fieldMergeRejectedReason", IncidentService.FIELD_MERGE_REJECTED_REASON));
    }

    @GetMapping
    public ResponseDTO<List<IncidentListItemDTO>> list() {
        return ResponseDTO.success(incidentService.list());
    }

    @GetMapping("/{id}")
    public ResponseDTO<IncidentDetailDTO> detail(@PathVariable Long id) {
        return ResponseDTO.success(incidentService.detail(id));
    }

    @GetMapping("/{id}/revisions")
    public ResponseDTO<List<IncidentRevisionDTO>> revisions(@PathVariable Long id) {
        return ResponseDTO.success(incidentService.revisions(id));
    }

    /** 建立事件：服务端冻结关联航线/值守/锚点快照，并写入第 1 版修订 */
    @PostMapping
    public ResponseDTO<IncidentDetailDTO> create(@RequestBody IncidentCreateDTO dto) {
        return ResponseDTO.success(incidentService.create(dto));
    }

    /** 正文修订（调查中编辑）；封存后同路径走“带理由更正”，旧版本保留 */
    @PutMapping("/{id}")
    public ResponseDTO<IncidentDetailDTO> edit(@PathVariable Long id, @RequestBody IncidentEditDTO dto) {
        return ResponseDTO.success(incidentService.edit(id, dto));
    }

    @PostMapping("/{id}/start-investigation")
    public ResponseDTO<IncidentDetailDTO> startInvestigation(@PathVariable Long id,
                                                             @RequestBody IncidentStatusActionDTO action) {
        return ResponseDTO.success(incidentService.startInvestigation(id, action));
    }

    @PostMapping("/{id}/submit-seal")
    public ResponseDTO<IncidentDetailDTO> submitForSeal(@PathVariable Long id,
                                                        @RequestBody IncidentStatusActionDTO action) {
        return ResponseDTO.success(incidentService.submitForSeal(id, action));
    }

    @PostMapping("/{id}/back-investigating")
    public ResponseDTO<IncidentDetailDTO> backToInvestigating(@PathVariable Long id,
                                                              @RequestBody IncidentStatusActionDTO action) {
        return ResponseDTO.success(incidentService.backToInvestigating(id, action));
    }

    /** 封存：仅安全主管，四项结论齐备，状态变更与 SEAL 修订同事务 */
    @PostMapping("/{id}/seal")
    public ResponseDTO<IncidentDetailDTO> seal(@PathVariable Long id,
                                               @RequestBody IncidentStatusActionDTO action) {
        return ResponseDTO.success(incidentService.seal(id, action));
    }

    /** 重新开启：仅安全主管，必须带依据 */
    @PostMapping("/{id}/reopen")
    public ResponseDTO<IncidentDetailDTO> reopen(@PathVariable Long id,
                                                 @RequestBody IncidentStatusActionDTO action) {
        return ResponseDTO.success(incidentService.reopen(id, action));
    }
}
