package com.px.base.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 人员列表条目：除档案与证书清单外，直接给出按参考时刻的“能否胜任某航线”
 * 试算结论，人员列表与航线入口、值守详情共用同一评估结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StaffViewDTO {
    private Long id;
    private String staffCode;
    private String staffName;
    private String staffRole;
    private String staffRoleLabel;
    private Integer status;
    private LocalDateTime referenceTime;
    private List<CertViewDTO> certs;

    /** 若带了航线试算参数（routeId + 起飞时刻），下面为资质结论；否则为 null */
    private QualificationView qualification;
}
