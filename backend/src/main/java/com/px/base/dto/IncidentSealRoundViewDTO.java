package com.px.base.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 封存轮次视图：第二次封存时，第 1 轮的 reopen* 已回填，
 * 配合两轮之间的 CORRECT/REOPEN 修订记录，可完整看出“两次封存之间发生了什么”。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentSealRoundViewDTO {
    private Integer roundNo;
    private LocalDateTime sealTime;
    private Long sealedById;
    private String sealedByName;
    private Long sealedVersion;
    private LocalDateTime reopenTime;
    private Long reopenedById;
    private String reopenedByName;
    private String reopenBasis;
    private Long reopenVersion;
    private boolean reopened;
}
