package com.px.base.security;

import com.px.base.entity.GroundStaff;
import com.px.base.repository.GroundStaffRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 鉴权口径验收：服务端只认请求头里的人员ID，角色一律以数据库档案为准，
 * 前端无法通过传参提权；未带头 401，越权 403。
 */
@ExtendWith(MockitoExtension.class)
class CurrentUserResolverTest {

    @Mock HttpServletRequest request;
    @Mock GroundStaffRepository staffRepository;

    @InjectMocks CurrentUserResolver resolver;

    private GroundStaff staff(long id, String role) {
        return GroundStaff.builder().id(id).staffCode("S" + id).staffName("某人")
                .staffRole(role).status(1).build();
    }

    @Test
    void 未携带操作人头时抛401() {
        when(request.getHeader("X-Staff-Id")).thenReturn(null);
        assertThatThrownBy(() -> resolver.require())
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void 角色以库档案为准_普通值班员不能自称为主管() {
        when(request.getHeader("X-Staff-Id")).thenReturn("7");
        when(staffRepository.findById(7L)).thenReturn(Optional.of(staff(7L, GroundStaff.ROLE_STATION_OFFICER)));

        CurrentUser user = resolver.require();
        assertThat(user.isSafetyOfficer()).isFalse();

        assertThatThrownBy(() -> resolver.requireSafetyOfficer("吊销"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void 主管身份同样由库解析_可通过主管校验() {
        when(request.getHeader("X-Staff-Id")).thenReturn("9");
        when(staffRepository.findById(9L)).thenReturn(Optional.of(staff(9L, GroundStaff.ROLE_SAFETY_OFFICER)));

        CurrentUser user = resolver.require();
        assertThat(user.isSafetyOfficer()).isTrue();
        resolver.requireSafetyOfficer("吊销"); // 不抛异常即通过
    }

    @Test
    void 非法人员标识抛401而不是403() {
        when(request.getHeader("X-Staff-Id")).thenReturn("abc");
        assertThatThrownBy(() -> resolver.require())
                .isInstanceOf(UnauthorizedException.class);
    }
}
