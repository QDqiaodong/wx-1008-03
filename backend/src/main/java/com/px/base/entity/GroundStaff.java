package com.px.base.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 地勤人员。角色只有两类：
 *  STATION_OFFICER 普通值班员（只能处理自己作为操作员的到位确认）
 *  SAFETY_OFFICER 安全主管（可吊销证书、取消已就绪值守）
 *
 * 角色判定以库中该人员档案为准，前端传什么角色都不作数。
 */
@Entity
@Table(name = "ground_staff")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroundStaff {

    public static final String ROLE_STATION_OFFICER = "STATION_OFFICER";
    public static final String ROLE_SAFETY_OFFICER = "SAFETY_OFFICER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "staff_code", unique = true, nullable = false, length = 50)
    private String staffCode;

    @Column(name = "staff_name", nullable = false, length = 50)
    private String staffName;

    @Column(name = "staff_role", nullable = false, length = 20)
    private String staffRole;

    @Column(name = "status")
    @Builder.Default
    private Integer status = 1;

    @Column(name = "create_time", updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        updateTime = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updateTime = LocalDateTime.now();
    }

    public boolean isSafetyOfficer() {
        return ROLE_SAFETY_OFFICER.equals(staffRole);
    }
}
