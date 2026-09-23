package com.px.base.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 建立异常事件请求。
 * 关联对象在建档时确定并冻结，后续不可通过任何接口修改：
 *  - routeId 必填（真实航线）；
 *  - watchId 可空：填写则必须属于该航线，值守的操作员/复核员并入“涉及人员”；
 *  - anchorIds 可空：必须为存在的锚点（不要求当时仍绑定该航线——以事件记录为准）；
 *  - involvedStaffIds 可空：现场涉及的其他值班员（与报告人、值守两人取并集）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentCreateDTO {
    private String title;
    private String severity;
    private String foundTime;

    private Long routeId;
    private Long watchId;
    private List<Long> anchorIds;
    private List<Long> involvedStaffIds;

    private String sceneNarrative;
    private String handlingActions;
    private String evidenceNote;

    /** 建档即可预填（草稿允许后补，不强制） */
    private String causeConclusion;
    private String correctiveAction;
    private Long ownerId;
    private String dueDate;
}
