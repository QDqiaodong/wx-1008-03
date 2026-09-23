package com.px.base.security;

import com.px.base.entity.GroundStaff;
import com.px.base.repository.GroundStaffRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 从请求头解析当前操作人：
 *   X-Staff-Id   人员ID（顶栏“当前操作人”选择后随每个请求携带）
 *
 * 只认人员ID，角色、姓名全部以数据库档案为准。
 * 这样即便有人绕过页面直接发请求，也无法伪造安全主管身份。
 */
@Component
@RequiredArgsConstructor
public class CurrentUserResolver {

    public static final String HEADER_STAFF_ID = "X-Staff-Id";

    private final HttpServletRequest request;
    private final GroundStaffRepository staffRepository;

    /** 未携带操作人时抛 401 */
    public CurrentUser require() {
        String raw = request.getHeader(HEADER_STAFF_ID);
        if (raw == null || raw.isBlank()) {
            throw new UnauthorizedException("未选择当前操作人，请在页面右上角选择身份后再操作");
        }
        long id;
        try {
            id = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new UnauthorizedException("当前操作人身份标识无效");
        }
        GroundStaff staff = staffRepository.findById(id)
                .orElseThrow(() -> new UnauthorizedException("当前操作人档案不存在或已删除"));
        if (staff.getStatus() == null || staff.getStatus() != 1) {
            throw new UnauthorizedException("当前操作人已停用，不能执行操作");
        }
        return CurrentUser.of(staff);
    }

    /** 已选择操作人则返回，否则返回 null（查询类接口允许匿名浏览） */
    public CurrentUser currentOrNull() {
        String raw = request.getHeader(HEADER_STAFF_ID);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return staffRepository.findById(Long.parseLong(raw.trim()))
                    .filter(s -> s.getStatus() != null && s.getStatus() == 1)
                    .map(CurrentUser::of)
                    .orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void requireSafetyOfficer(String action) {
        CurrentUser user = require();
        if (!user.isSafetyOfficer()) {
            throw new ForbiddenException(String.format(
                    "无权%s：仅安全主管可执行该操作，当前操作人「%s」是普通值班员", action, user.name()));
        }
    }

    public void requireSelf(Long targetStaffId, String action) {
        CurrentUser user = require();
        if (!user.id().equals(targetStaffId) && !user.isSafetyOfficer()) {
            throw new ForbiddenException(String.format(
                    "无权%s：普通值班员只能处理自己的确认，当前操作人「%s」不是本人", action, user.name()));
        }
    }
}
