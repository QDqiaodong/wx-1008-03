package com.px.base.controller;

import com.px.base.dto.IncidentCreateDTO;
import com.px.base.dto.IncidentEditDTO;
import com.px.base.dto.IncidentListItemDTO;
import com.px.base.dto.IncidentTransitionDTO;
import com.px.base.dto.IncidentViewDTO;
import com.px.base.dto.ResponseDTO;
import com.px.base.service.IncidentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 飞行异常事件复盘接口。
 * 所有接口的权限、版本、状态校验都在服务端完成：
 *  - 列表/详情：普通值班员仅能读到自己报告或参与的事件，越权直接 403；
 *  - 封存/重新开启：仅安全主管，携带旧版本号或越权分别得到 409/403；
 *  - 正文修订：已封存拒绝覆盖，重开态更正必须带理由。
 */
@RestController
@RequestMapping("/api/incident")
@RequiredArgsConstructor
public class IncidentController {

    private final IncidentService incidentService;

    @GetMapping
    public ResponseDTO<List<IncidentListItemDTO>> list() {
        return ResponseDTO.success(incidentService.list());
    }

    @GetMapping("/{id}")
    public ResponseDTO<IncidentViewDTO> get(@PathVariable Long id) {
        return ResponseDTO.success(incidentService.get(id));
    }

    @PostMapping
    public ResponseDTO<IncidentViewDTO> create(@RequestBody IncidentCreateDTO dto) {
        return ResponseDTO.success(incidentService.create(dto));
    }

    /** 正文修订（草稿补充 / 调查中修订 / 重新开启后的封存更正） */
    @PostMapping("/{id}/edit")
    public ResponseDTO<IncidentViewDTO> edit(@PathVariable Long id, @RequestBody IncidentEditDTO dto) {
        return ResponseDTO.success(incidentService.editContent(id, dto));
    }

    /** 草稿 → 调查中 */
    @PostMapping("/{id}/enter-investigating")
    public ResponseDTO<IncidentViewDTO> enterInvestigating(@PathVariable Long id,
                                                           @RequestBody(required = false) IncidentTransitionDTO dto) {
        return ResponseDTO.success(incidentService.enterInvestigating(id, dtoOrEmpty(dto)));
    }

    /** 调查中 → 待封存（原因结论/纠正措施/负责人/期限齐全） */
    @PostMapping("/{id}/request-seal")
    public ResponseDTO<IncidentViewDTO> requestSeal(@PathVariable Long id,
                                                    @RequestBody(required = false) IncidentTransitionDTO dto) {
        return ResponseDTO.success(incidentService.requestSeal(id, dtoOrEmpty(dto)));
    }

    /** 待封存 → 退回调查中（还要补查） */
    @PostMapping("/{id}/back-investigating")
    public ResponseDTO<IncidentViewDTO> backToInvestigating(@PathVariable Long id,
                                                            @RequestBody(required = false) IncidentTransitionDTO dto) {
        return ResponseDTO.success(incidentService.backToInvestigating(id, dtoOrEmpty(dto)));
    }

    /** 待封存 → 已封存（仅安全主管） */
    @PostMapping("/{id}/seal")
    public ResponseDTO<IncidentViewDTO> seal(@PathVariable Long id,
                                             @RequestBody(required = false) IncidentTransitionDTO dto) {
        return ResponseDTO.success(incidentService.seal(id, dtoOrEmpty(dto)));
    }

    /** 已封存 → 重新开启（仅安全主管，依据必填） */
    @PostMapping("/{id}/reopen")
    public ResponseDTO<IncidentViewDTO> reopen(@PathVariable Long id,
                                               @RequestBody(required = false) IncidentTransitionDTO dto) {
        return ResponseDTO.success(incidentService.reopen(id, dtoOrEmpty(dto)));
    }

    private IncidentTransitionDTO dtoOrEmpty(IncidentTransitionDTO dto) {
        return dto == null ? new IncidentTransitionDTO() : dto;
    }
}
