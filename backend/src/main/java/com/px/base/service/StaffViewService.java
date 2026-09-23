package com.px.base.service;

import com.px.base.dto.CertViewDTO;
import com.px.base.dto.QualificationView;
import com.px.base.dto.StaffViewDTO;
import com.px.base.entity.FlightRoute;
import com.px.base.entity.GroundCert;
import com.px.base.entity.GroundStaff;
import com.px.base.repository.FlightRouteRepository;
import com.px.base.rule.QualificationEvaluator;
import com.px.base.rule.QualificationResult;
import com.px.base.rule.WatchPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 人员列表组装。支持带 routeId + 起飞时刻的“胜任度试算”：
 * 与值守详情、航线入口使用同一个 QualificationEvaluator，
 * 因此重新打开页面后三处对同一天、同一条航线的资格结论必然一致。
 */
@Service
@RequiredArgsConstructor
public class StaffViewService {

    private final GroundStaffService staffService;
    private final GroundCertService certService;
    private final FlightRouteRepository routeRepository;
    private final WatchRequalifier requalifier;

    public List<StaffViewDTO> list(Long routeId, LocalDateTime takeoff) {
        LocalDateTime referenceTime = takeoff != null ? takeoff : LocalDateTime.now();

        FlightRoute trialRoute = null;
        Set<String> trialZones = Set.of();
        if (routeId != null) {
            trialRoute = routeRepository.findById(routeId).orElse(null);
            if (trialRoute == null) {
                throw new IllegalArgumentException("试算航线不存在: " + routeId);
            }
            trialZones = requalifier.requiredZones(routeId);
        }
        final FlightRoute effectiveTrialRoute = trialRoute;
        final Set<String> effectiveTrialZones = trialZones;

        return staffService.findAll().stream()
                .map(staff -> {
                    List<GroundCert> certs = certService.findByStaff(staff.getId());
                    List<CertViewDTO> certViews = certs.stream()
                            .map(c -> certService.toView(c, staff, referenceTime))
                            .toList();

                    StaffViewDTO.StaffViewDTOBuilder b = StaffViewDTO.builder()
                            .id(staff.getId())
                            .staffCode(staff.getStaffCode())
                            .staffName(staff.getStaffName())
                            .staffRole(staff.getStaffRole())
                            .staffRoleLabel(GroundStaff.ROLE_SAFETY_OFFICER.equals(staff.getStaffRole())
                                    ? "安全主管" : "普通值班员")
                            .status(staff.getStatus())
                            .referenceTime(referenceTime)
                            .certs(certViews);

                    if (effectiveTrialRoute != null) {
                        QualificationResult result = QualificationEvaluator.evaluate(
                                staff.getId(), staff.getStaffName(), certs,
                                effectiveTrialRoute.getWindLevel(), effectiveTrialZones, referenceTime);
                        b.qualification(toView(result));
                    }
                    return b.build();
                })
                .toList();
    }

    private QualificationView toView(QualificationResult q) {
        return QualificationView.builder()
                .staffId(q.staffId())
                .staffName(q.staffName())
                .qualified(q.qualified())
                .activeCertNo(q.activeCertNo())
                .activeCertStatus(q.activeCertStatus())
                .coveredWindLevels(q.coveredWindLevels())
                .coveredZones(q.coveredZones())
                .missingWindLevels(q.missingWindLevels())
                .missingZones(q.missingZones())
                .detailMessages(q.detailMessages())
                .build();
    }

    public String policy() {
        return WatchPolicy.TAKEOFF_POLICY;
    }

    public String rejectedPolicyReason() {
        return WatchPolicy.REJECTED_POLICY_REASON;
    }
}
