package com.px.base.service;

import com.px.base.entity.FlightWatch;
import com.px.base.entity.GroundCert;
import com.px.base.entity.GroundStaff;
import com.px.base.rule.QualificationResult;
import com.px.base.repository.AnchorRepository;
import com.px.base.repository.FlightRouteRepository;
import com.px.base.repository.FlightWatchRepository;
import com.px.base.repository.GroundCertRepository;
import com.px.base.repository.RouteAnchorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 证书吊销后重新判定验收：
 *  未来未就绪安排立即打回草拟并清空到位；
 *  已就绪/已取消历史不动，快照保留；不涉及该人员的不动。
 */
@ExtendWith(MockitoExtension.class)
class WatchRequalifierTest {

    @Mock FlightWatchRepository watchRepository;
    @Mock FlightRouteRepository routeRepository;
    @Mock RouteAnchorRepository routeAnchorRepository;
    @Mock AnchorRepository anchorRepository;
    @Mock GroundCertRepository certRepository;

    @InjectMocks WatchRequalifier requalifier;

    private FlightWatch watch(long id, String status, long operatorId, long reviewerId, LocalDate date) {
        return FlightWatch.builder()
                .id(id).routeId(1L).routeCode("R1")
                .flightDate(date).plannedTakeoff(date.atTime(9, 0))
                .operatorId(operatorId).operatorName("赵")
                .reviewerId(reviewerId).reviewerName("钱")
                .status(status).operatorArrived(1).build();
    }

    private GroundCert revokedCert(long staffId) {
        return GroundCert.builder()
                .id(5L).certNo("C-GONE").staffId(staffId)
                .windLevels("强风").anchorZones("西区")
                .effectiveDate(LocalDate.now().minusDays(1))
                .expiryDate(LocalDate.now().plusDays(10))
                .revoked(1).build();
    }

    private QualificationResult unqualified(long id, String name) {
        return new QualificationResult(id, name, false, "C-GONE", "REVOKED",
                List.of("强风"), List.of("西区"), List.of(), List.of(),
                List.of(String.format("人员「%s」的证书C-GONE已被吊销", name)));
    }

    @Test
    void 吊销后_未来待复核值守打回草拟并清空到位() {
        FlightWatch pending = watch(10L, FlightWatch.STATUS_PENDING_REVIEW, 1L, 2L,
                LocalDate.now().plusDays(1));
        when(watchRepository.findByFlightDateAfterAndStatusIn(any(), any())).thenReturn(List.of(pending));
        when(routeAnchorRepository.findByRouteIdAndStatus(eq(1L), eq(1))).thenReturn(List.of());
        when(certRepository.findByStaffId(1L)).thenReturn(List.of(revokedCert(1L)));
        when(certRepository.findByStaffId(2L)).thenReturn(List.of(
                GroundCert.builder().certNo("C-OK").staffId(2L).windLevels("强风")
                        .anchorZones("西区").effectiveDate(LocalDate.now().minusDays(1))
                        .expiryDate(LocalDate.now().plusDays(10)).revoked(0).build()));

        int affected = requalifier.requalifyAfterRevocation(1L, "C-GONE");

        assertThat(affected).isEqualTo(1);
        assertThat(pending.getStatus()).isEqualTo(FlightWatch.STATUS_DRAFT);
        assertThat(pending.getOperatorArrived()).isZero();
        assertThat(pending.getArrivalTime()).isNull();
        assertThat(pending.getRequalifyReason()).contains("C-GONE", "吊销");
        verify(watchRepository).save(pending);
    }

    @Test
    void 吊销后_已就绪历史值守完全不动_快照保留() {
        FlightWatch ready = watch(20L, FlightWatch.STATUS_READY, 1L, 2L,
                LocalDate.now().plusDays(1));
        ready.setOperatorSnapshotCertNo("C-OLD-SNAPSHOT");
        // 即使仓储层误把终态带进来，服务层也必须挡住
        when(watchRepository.findByFlightDateAfterAndStatusIn(any(), any())).thenReturn(List.of(ready));

        int affected = requalifier.requalifyAfterRevocation(1L, "C-GONE");

        assertThat(affected).isZero();
        assertThat(ready.getStatus()).isEqualTo(FlightWatch.STATUS_READY);
        assertThat(ready.getOperatorSnapshotCertNo()).isEqualTo("C-OLD-SNAPSHOT");
        verify(watchRepository, never()).save(any());
        verify(certRepository, never()).findByStaffId(any());
    }

    @Test
    void 吊销后_不涉及该人员的值守不动() {
        FlightWatch other = watch(30L, FlightWatch.STATUS_DRAFT, 8L, 9L, LocalDate.now().plusDays(1));
        when(watchRepository.findByFlightDateAfterAndStatusIn(any(), any())).thenReturn(List.of(other));

        int affected = requalifier.requalifyAfterRevocation(1L, "C-GONE");

        assertThat(affected).isZero();
        assertThat(other.getStatus()).isEqualTo(FlightWatch.STATUS_DRAFT);
        verify(watchRepository, never()).save(any());
    }
}
