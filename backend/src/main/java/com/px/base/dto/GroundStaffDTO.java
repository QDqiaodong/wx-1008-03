package com.px.base.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroundStaffDTO {
    private String staffCode;
    private String staffName;
    /** STATION_OFFICER / SAFETY_OFFICER */
    private String staffRole;
}
