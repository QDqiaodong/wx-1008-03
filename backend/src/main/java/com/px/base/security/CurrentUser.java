package com.px.base.security;

import com.px.base.entity.GroundStaff;

/**
 * 当前操作人。角色一律从库中人员档案解析，请求头只提供人员ID，
 * 前端无法通过传角色提权。
 */
public record CurrentUser(Long id, String name, String role) {

    public static CurrentUser of(GroundStaff staff) {
        return new CurrentUser(staff.getId(), staff.getStaffName(), staff.getStaffRole());
    }

    public boolean isSafetyOfficer() {
        return GroundStaff.ROLE_SAFETY_OFFICER.equals(role);
    }
}
