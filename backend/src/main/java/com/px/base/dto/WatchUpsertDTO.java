package com.px.base.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 值守创建/改派。plannedTakeoff 为预计起飞时刻（可与 plannedEnd 跨午夜）；
 * flightDate 不传时后端一律从起飞时刻推导，保证“飞行日”口径与跨午夜口径一致。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatchUpsertDTO {
    private Long routeId;
    private Long operatorId;
    private Long reviewerId;
    private LocalDateTime plannedTakeoff;
    private LocalDateTime plannedEnd;
}
