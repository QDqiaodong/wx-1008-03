package com.px.base.dto;

import com.px.base.entity.IncidentEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 事件【正文】的整份内容快照。
 *
 * 既是客户端编辑提交的载体，也是修订记录 before_content/after_content 的存储结构：
 * 修订历史保存的就是这个对象序列化后的 JSON，因此“并排对比旧版/新版”无需回放事件行，
 * 直接取两条修订记录的 content 逐字段比对即可。标题也纳入正文，改名同样留痕。
 *
 * 关联对象（航线/值守/锚点/涉及人员）不在这里——它们在建档时冻结，永不通过正文修订改变。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentContentDTO {
    private String title;
    private String severity;
    /** 发现时间 ISO：2026-09-23T14:30:00 */
    private String foundTime;
    private String sceneNarrative;
    private String handlingActions;
    private String evidenceNote;
    private String causeConclusion;
    private String correctiveAction;
    private Long ownerId;
    private LocalDate dueDate;

    /**
     * 从事件当前行提取正文（ownerName 由服务端解析人员档案后冻结，不采信前端传值）
     */
    public static IncidentContentDTO of(IncidentEvent e) {
        return IncidentContentDTO.builder()
                .title(e.getTitle())
                .severity(e.getSeverity())
                .foundTime(e.getFoundTime() == null ? null : e.getFoundTime().toString())
                .sceneNarrative(e.getSceneNarrative())
                .handlingActions(e.getHandlingActions())
                .evidenceNote(e.getEvidenceNote())
                .causeConclusion(e.getCauseConclusion())
                .correctiveAction(e.getCorrectiveAction())
                .ownerId(e.getOwnerId())
                .dueDate(e.getDueDate())
                .build();
    }
}
