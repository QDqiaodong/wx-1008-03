package com.px.base.service;

import com.px.base.dto.CertViewDTO;
import com.px.base.dto.GroundCertDTO;
import com.px.base.entity.GroundCert;
import com.px.base.entity.GroundStaff;
import com.px.base.repository.GroundCertRepository;
import com.px.base.repository.GroundStaffRepository;
import com.px.base.rule.Csv;
import com.px.base.rule.WatchPolicy;
import com.px.base.security.CurrentUser;
import com.px.base.security.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class GroundCertService {

    private final GroundCertRepository certRepository;
    private final GroundStaffRepository staffRepository;
    private final CurrentUserResolver currentUserResolver;
    private final WatchRequalifier requalifier;

    @Transactional
    public GroundCert create(GroundCertDTO dto) {
        currentUserResolver.require();
        validate(dto);
        GroundStaff staff = staffRepository.findById(dto.getStaffId())
                .orElseThrow(() -> new IllegalArgumentException("持证人员不存在: " + dto.getStaffId()));
        if (certRepository.existsByCertNo(dto.getCertNo().trim())) {
            throw new IllegalArgumentException("证书编号已存在: " + dto.getCertNo());
        }
        GroundCert cert = GroundCert.builder()
                .certNo(dto.getCertNo().trim())
                .staffId(staff.getId())
                .windLevels(Csv.join(dto.getWindLevels()))
                .anchorZones(Csv.join(dto.getAnchorZones()))
                .effectiveDate(dto.getEffectiveDate())
                .expiryDate(dto.getExpiryDate())
                .revoked(0)
                .build();
        GroundCert saved = certRepository.save(cert);
        log.info("创建资质证: {} 持有人={} 风级={} 区域={}",
                saved.getCertNo(), staff.getStaffName(), saved.getWindLevels(), saved.getAnchorZones());
        return saved;
    }

    /**
     * 吊销证书：仅安全主管可执行（服务端强制）。
     * 吊销后立刻重新判定该人员未来飞行日尚未就绪的值守，已就绪/已取消/已过去的不动。
     */
    @Transactional
    public int revoke(Long certId, String reason) {
        CurrentUser officer = currentUserResolver.require();
        if (!officer.isSafetyOfficer()) {
            throw new com.px.base.security.ForbiddenException(String.format(
                    "无权吊销证书：仅安全主管可吊销，当前操作人「%s」是普通值班员", officer.name()));
        }
        GroundCert cert = certRepository.findById(certId)
                .orElseThrow(() -> new IllegalArgumentException("证书不存在: " + certId));
        if (cert.isRevoked()) {
            throw new IllegalArgumentException("证书已处于吊销状态，不能重复吊销");
        }
        cert.setRevoked(1);
        cert.setRevokeTime(LocalDateTime.now());
        cert.setRevokeReason(reason);
        cert.setRevokedById(officer.id());
        cert.setRevokedByName(officer.name());
        certRepository.save(cert);

        int affected = requalifier.requalifyAfterRevocation(cert.getStaffId(), cert.getCertNo());
        log.warn("安全主管{}吊销证书{}，未来未就绪值守打回{}个", officer.name(), cert.getCertNo(), affected);
        return affected;
    }

    public List<GroundCert> findByStaff(Long staffId) {
        return certRepository.findByStaffIdOrderByExpiryDateDesc(staffId);
    }

    public CertViewDTO toView(GroundCert cert, GroundStaff staff, LocalDateTime referenceTime) {
        LocalDateTime ref = referenceTime != null ? referenceTime : LocalDateTime.now();
        return CertViewDTO.builder()
                .id(cert.getId())
                .certNo(cert.getCertNo())
                .staffId(cert.getStaffId())
                .staffName(staff != null ? staff.getStaffName() : String.valueOf(cert.getStaffId()))
                .windLevels(Csv.split(cert.getWindLevels()))
                .anchorZones(Csv.split(cert.getAnchorZones()))
                .effectiveDate(cert.getEffectiveDate())
                .expiryDate(cert.getExpiryDate())
                .status(cert.deriveStatus(ref))
                .referenceTime(ref)
                .revokeTime(cert.getRevokeTime())
                .revokeReason(cert.getRevokeReason())
                .revokedByName(cert.getRevokedByName())
                .build();
    }

    private void validate(GroundCertDTO dto) {
        if (dto.getCertNo() == null || dto.getCertNo().isBlank()) {
            throw new IllegalArgumentException("证书编号不能为空");
        }
        if (dto.getStaffId() == null) {
            throw new IllegalArgumentException("必须选择持证人员");
        }
        if (dto.getWindLevels() == null || dto.getWindLevels().isEmpty()) {
            throw new IllegalArgumentException("证书至少要写明一个适用风级");
        }
        for (String level : dto.getWindLevels()) {
            if (!WatchPolicy.WIND_LEVELS.contains(level.trim())) {
                throw new IllegalArgumentException("非法风级: " + level);
            }
        }
        if (dto.getAnchorZones() == null || dto.getAnchorZones().isEmpty()) {
            throw new IllegalArgumentException("证书至少要写明一个可负责的锚点区域");
        }
        if (dto.getEffectiveDate() == null || dto.getExpiryDate() == null) {
            throw new IllegalArgumentException("必须填写生效日与到期日");
        }
        if (dto.getExpiryDate().isBefore(dto.getEffectiveDate())) {
            throw new IllegalArgumentException("到期日不能早于生效日");
        }
    }
}
