package com.px.base.service;

import com.px.base.entity.FlightRoute;
import com.px.base.entity.FlightWatch;
import com.px.base.entity.GroundStaff;
import com.px.base.rule.QualificationResult;
import com.px.base.repository.FlightRouteRepository;
import com.px.base.repository.FlightWatchRepository;
import com.px.base.repository.GroundCertRepository;
import com.px.base.repository.GroundStaffRepository;
import com.px.base.security.CurrentUser;
import com.px.base.security.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 开航值守服务端强制执行验收：
 *  同一人两职拒绝、到位顺序闸门、就绪逐项资质、操作员不能自复核、
 *  普通值班员不能取消就绪、安全主管可取消就绪并保留快照。
 */
@ExtendWith(MockitoExtension.class)
class FlightWatchServiceTest {

    @Mock FlightWatchRepository watchRepository;
    @Mock FlightRouteRepository routeRepository;
    @Mock GroundStaffRepository staffRepository;
    @Mock com.px.base.security.CurrentUserResolver currentUserResolver;
    @Mock WatchRequalifier requalifier;

    @InjectMocks FlightWatchService service;

    private GroundStaff officer(long id, String name, String role) {
        return GroundStaff.builder().id(id).staffCode("S" + id).staffName(name).staffRole(role).status(1).build();
    }

    private FlightRoute route() {
        return FlightRoute.builder().id(1L).routeCode("R1").routeName("测试线")
                .windLevel("强风").status(1).build();
    }

    private FlightWatch watch(long id, String status, long operatorId, long reviewerId) {
        return FlightWatch.builder()
                .id(id).routeId(1L).routeCode("R1")
                .flightDate(LocalDate.now().plusDays(1))
                .plannedTakeoff(LocalDate.now().plusDays(1).atTime(9, 0))
                .plannedEnd(LocalDate.now().plusDays(1).atTime(11, 0))
                .operatorId(operatorId).operatorName("操作员")
                .reviewerId(reviewerId).reviewerName("复核员")
                .status(status).operatorArrived(0).reviewerArrived(0)
                .build();
    }

    private QualificationResult qualified(long staffId, String name, String certNo) {
        return new QualificationResult(staffId, name, true, certNo, "VALID",
                List.of("强风"), List.of("西区"), List.of(), List.of(), List.of());
    }

    private QualificationResult gap(long staffId, String name) {
        return new QualificationResult(staffId, name, false, "C-GAP", "VALID",
                List.of("微风"), List.of("东区"),
                List.of("强风"), List.of("西区"),
                List.of(String.format("人员「%s」的证书C-GAP未覆盖在用锚点区域：西区", name)));
    }

    @BeforeEach
    void setUp() {
        lenient().when(routeRepository.findById(1L)).thenReturn(Optional.of(route()));
    }

    @Test
    void 创建值守_同一人担任两个职责被明确拒绝() {
        GroundStaff zhao = officer(1L, "赵", GroundStaff.ROLE_STATION_OFFICER);
        when(currentUserResolver.require()).thenReturn(CurrentUser.of(zhao));
        when(staffRepository.findById(1L)).thenReturn(Optional.of(zhao));

        com.px.base.dto.WatchUpsertDTO dto = com.px.base.dto.WatchUpsertDTO.builder()
                .routeId(1L).operatorId(1L).reviewerId(1L)
                .plannedTakeoff(LocalDate.now().plusDays(1).atTime(9, 0))
                .build();

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为同一人");
        verify(watchRepository, never()).save(any());
    }

    @Test
    void 操作员未到位时复核员不能就绪() {
        GroundStaff reviewer = officer(2L, "钱", GroundStaff.ROLE_STATION_OFFICER);
        when(currentUserResolver.require()).thenReturn(CurrentUser.of(reviewer));
        FlightWatch w = watch(10L, FlightWatch.STATUS_DRAFT, 1L, 2L);
        when(watchRepository.findById(10L)).thenReturn(Optional.of(w));

        assertThatThrownBy(() -> service.reviewerReady(10L))
                .isInstanceOf(com.px.base.security.BusinessConflictException.class)
                .hasMessageContaining("尚未确认现场到位");
        verify(watchRepository, never()).save(any());
    }

    @Test
    void 操作员到位但资质有缺口时不能就绪_逐项原因抛出() {
        GroundStaff reviewer = officer(2L, "钱", GroundStaff.ROLE_STATION_OFFICER);
        when(currentUserResolver.require()).thenReturn(CurrentUser.of(reviewer));
        FlightWatch w = watch(10L, FlightWatch.STATUS_PENDING_REVIEW, 1L, 2L);
        w.setOperatorArrived(1);
        when(watchRepository.findById(10L)).thenReturn(Optional.of(w));

        QualificationResult opOk = qualified(1L, "赵", "C-OP");
        QualificationResult rvGap = gap(2L, "钱");
        when(requalifier.recheck(w))
                .thenReturn(new WatchRequalifier.RecheckResult(opOk, rvGap, "强风", Set.of("西区"), rvGap.detailMessages()));

        assertThatThrownBy(() -> service.reviewerReady(10L))
                .isInstanceOf(com.px.base.security.BusinessConflictException.class)
                .hasMessageContaining("资质不满足")
                .hasMessageContaining("西区");
        verify(watchRepository, never()).save(any());
    }

    @Test
    void 资质齐备且操作员到位时就绪_并冻结证书快照() {
        GroundStaff reviewer = officer(2L, "钱", GroundStaff.ROLE_STATION_OFFICER);
        GroundStaff operator = officer(1L, "赵", GroundStaff.ROLE_STATION_OFFICER);
        when(currentUserResolver.require()).thenReturn(CurrentUser.of(reviewer));
        FlightWatch w = watch(10L, FlightWatch.STATUS_PENDING_REVIEW, 1L, 2L);
        w.setOperatorArrived(1);
        when(watchRepository.findById(10L)).thenReturn(Optional.of(w));
        when(staffRepository.findById(1L)).thenReturn(Optional.of(operator));
        when(staffRepository.findById(2L)).thenReturn(Optional.of(reviewer));

        QualificationResult opOk = qualified(1L, "赵", "C-OP");
        QualificationResult rvOk = qualified(2L, "钱", "C-RV");
        when(requalifier.recheck(w))
                .thenReturn(new WatchRequalifier.RecheckResult(opOk, rvOk, "强风", Set.of("西区"), List.of()));

        service.reviewerReady(10L);

        assertThat(w.getStatus()).isEqualTo(FlightWatch.STATUS_READY);
        assertThat(w.getOperatorSnapshotCertNo()).isEqualTo("C-OP");
        assertThat(w.getReviewerSnapshotCertNo()).isEqualTo("C-RV");
        assertThat(w.getSnapshotRequiredZones()).contains("西区");
        assertThat(w.getSnapshotRouteWindLevel()).isEqualTo("强风");
        verify(watchRepository).save(w);
    }

    @Test
    void 操作员不能复核自己的工作_即使被当作复核员调用() {
        GroundStaff zhao = officer(1L, "赵", GroundStaff.ROLE_STATION_OFFICER);
        when(currentUserResolver.require()).thenReturn(CurrentUser.of(zhao));
        // 排班合法（两人不同），但当前操作人是操作员 1 而不是复核员 2
        FlightWatch w = watch(10L, FlightWatch.STATUS_PENDING_REVIEW, 1L, 2L);
        w.setOperatorArrived(1);
        when(watchRepository.findById(10L)).thenReturn(Optional.of(w));

        assertThatThrownBy(() -> service.reviewerReady(10L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("不是复核员");
    }

    @Test
    void 安全主管也不能代行复核确认就绪() {
        GroundStaff chief = officer(9L, "孙", GroundStaff.ROLE_SAFETY_OFFICER);
        when(currentUserResolver.require()).thenReturn(CurrentUser.of(chief));
        FlightWatch w = watch(10L, FlightWatch.STATUS_PENDING_REVIEW, 1L, 2L);
        w.setOperatorArrived(1);
        when(watchRepository.findById(10L)).thenReturn(Optional.of(w));

        assertThatThrownBy(() -> service.reviewerReady(10L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("不是复核员");
        verify(requalifier, never()).recheck(any());
    }

    @Test
    void 普通值班员不能确认他人的到位() {
        GroundStaff other = officer(3L, "路人", GroundStaff.ROLE_STATION_OFFICER);
        when(currentUserResolver.require()).thenReturn(CurrentUser.of(other));
        FlightWatch w = watch(10L, FlightWatch.STATUS_DRAFT, 1L, 2L);
        when(watchRepository.findById(10L)).thenReturn(Optional.of(w));

        assertThatThrownBy(() -> service.operatorArrive(10L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("只有操作员本人");
        verify(watchRepository, never()).save(any());
    }

    @Test
    void 普通值班员不能取消已就绪值守_且快照保持不变() {
        GroundStaff zhao = officer(1L, "赵", GroundStaff.ROLE_STATION_OFFICER);
        when(currentUserResolver.require()).thenReturn(CurrentUser.of(zhao));
        FlightWatch w = watch(10L, FlightWatch.STATUS_READY, 1L, 2L);
        w.setOperatorSnapshotCertNo("C-OLD");
        when(watchRepository.findById(10L)).thenReturn(Optional.of(w));

        assertThatThrownBy(() -> service.cancel(10L, "想取消"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("只能由安全主管取消");
        assertThat(w.getStatus()).isEqualTo(FlightWatch.STATUS_READY);
        assertThat(w.getOperatorSnapshotCertNo()).isEqualTo("C-OLD");
        verify(watchRepository, never()).save(any());
    }

    @Test
    void 安全主管可以取消已就绪值守() {
        GroundStaff chief = officer(9L, "孙", GroundStaff.ROLE_SAFETY_OFFICER);
        when(currentUserResolver.require()).thenReturn(CurrentUser.of(chief));
        FlightWatch w = watch(10L, FlightWatch.STATUS_READY, 1L, 2L);
        when(watchRepository.findById(10L)).thenReturn(Optional.of(w));

        service.cancel(10L, "天气突变");

        assertThat(w.getStatus()).isEqualTo(FlightWatch.STATUS_CANCELLED);
        assertThat(w.getCancelledByName()).isEqualTo("孙");
        assertThat(w.getCancelReason()).isEqualTo("天气突变");
        verify(watchRepository).save(w);
    }

    @Test
    void 与值守无关的普通值班员连未就绪值守也不能取消() {
        GroundStaff other = officer(3L, "路人", GroundStaff.ROLE_STATION_OFFICER);
        when(currentUserResolver.require()).thenReturn(CurrentUser.of(other));
        FlightWatch w = watch(10L, FlightWatch.STATUS_DRAFT, 1L, 2L);
        when(watchRepository.findById(10L)).thenReturn(Optional.of(w));

        assertThatThrownBy(() -> service.cancel(10L, null))
                .isInstanceOf(ForbiddenException.class);
        verify(watchRepository, never()).save(any());
    }

    @Test
    void 终态值守不能改派() {
        GroundStaff chief = officer(9L, "孙", GroundStaff.ROLE_SAFETY_OFFICER);
        when(currentUserResolver.require()).thenReturn(CurrentUser.of(chief));
        FlightWatch w = watch(10L, FlightWatch.STATUS_READY, 1L, 2L);
        when(watchRepository.findById(10L)).thenReturn(Optional.of(w));

        com.px.base.dto.WatchUpsertDTO dto = com.px.base.dto.WatchUpsertDTO.builder()
                .routeId(1L).operatorId(1L).reviewerId(2L)
                .plannedTakeoff(LocalDate.now().plusDays(2).atTime(9, 0))
                .build();
        assertThatThrownBy(() -> service.update(10L, dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("终态");
        verify(watchRepository, never()).save(any());
    }
}
