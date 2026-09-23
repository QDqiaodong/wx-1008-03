package com.px.base.service;

import com.px.base.entity.GroundCert;
import com.px.base.entity.GroundStaff;
import com.px.base.repository.GroundCertRepository;
import com.px.base.repository.GroundStaffRepository;
import com.px.base.security.CurrentUser;
import com.px.base.security.CurrentUserResolver;
import com.px.base.security.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 吊销权限验收：只有安全主管能吊销；普通值班员直接发起吊销时数据不得变化。 */
@ExtendWith(MockitoExtension.class)
class GroundCertServiceTest {

    @Mock GroundCertRepository certRepository;
    @Mock GroundStaffRepository staffRepository;
    @Mock CurrentUserResolver currentUserResolver;
    @Mock WatchRequalifier requalifier;

    @InjectMocks GroundCertService certService;

    private GroundCert cert() {
        return GroundCert.builder()
                .id(1L).certNo("C1").staffId(2L)
                .windLevels("强风").anchorZones("西区")
                .effectiveDate(LocalDate.now().minusDays(1))
                .expiryDate(LocalDate.now().plusDays(10))
                .revoked(0).build();
    }

    private GroundStaff staff(long id, String role) {
        return GroundStaff.builder().id(id).staffCode("S" + id).staffName(role.equals("SAFETY_OFFICER") ? "孙" : "赵")
                .staffRole(role).status(1).build();
    }

    @Test
    void 普通值班员吊销被拒绝且不改任何数据() {
        when(currentUserResolver.require()).thenReturn(CurrentUser.of(staff(2L, GroundStaff.ROLE_STATION_OFFICER)));

        assertThatThrownBy(() -> certService.revoke(1L, "想吊销"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("仅安全主管");

        verify(certRepository, never()).save(any());
        verify(certRepository, never()).findById(any());
        verify(requalifier, never()).requalifyAfterRevocation(any(), any());
    }

    @Test
    void 安全主管吊销成功并触发未来值守重判定() {
        GroundStaff chief = staff(9L, GroundStaff.ROLE_SAFETY_OFFICER);
        when(currentUserResolver.require()).thenReturn(CurrentUser.of(chief));
        GroundCert c = cert();
        when(certRepository.findById(1L)).thenReturn(Optional.of(c));
        when(requalifier.requalifyAfterRevocation(2L, "C1")).thenReturn(2);

        int affected = certService.revoke(1L, "违规作业");

        assertThat(affected).isEqualTo(2);
        assertThat(c.getRevoked()).isEqualTo(1);
        assertThat(c.getRevokeReason()).isEqualTo("违规作业");
        assertThat(c.getRevokedByName()).isEqualTo("孙");
        verify(certRepository).save(c);
        verify(requalifier).requalifyAfterRevocation(2L, "C1");
    }

    @Test
    void 已吊销证书不能重复吊销() {
        GroundStaff chief = staff(9L, GroundStaff.ROLE_SAFETY_OFFICER);
        when(currentUserResolver.require()).thenReturn(CurrentUser.of(chief));
        GroundCert c = cert();
        c.setRevoked(1);
        when(certRepository.findById(1L)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> certService.revoke(1L, "再来一次"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已处于吊销");
        verify(requalifier, never()).requalifyAfterRevocation(any(), any());
    }
}
