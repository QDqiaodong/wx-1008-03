package com.px.base.service;

import com.px.base.dto.GroundStaffDTO;
import com.px.base.entity.GroundStaff;
import com.px.base.repository.GroundStaffRepository;
import com.px.base.security.CurrentUserResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class GroundStaffService {

    private final GroundStaffRepository staffRepository;
    private final CurrentUserResolver currentUserResolver;

    @Transactional
    public GroundStaff create(GroundStaffDTO dto) {
        // 建档只要求是登录人员；角色由建档表单录入，操作人自身的角色不影响
        currentUserResolver.require();
        validate(dto);
        if (staffRepository.existsByStaffCode(dto.getStaffCode().trim())) {
            throw new IllegalArgumentException("人员编号已存在: " + dto.getStaffCode());
        }
        GroundStaff staff = GroundStaff.builder()
                .staffCode(dto.getStaffCode().trim())
                .staffName(dto.getStaffName().trim())
                .staffRole(dto.getStaffRole())
                .status(1)
                .build();
        GroundStaff saved = staffRepository.save(staff);
        log.info("创建地勤人员: {}({}) 角色={}", saved.getStaffName(), saved.getStaffCode(), saved.getStaffRole());
        return saved;
    }

    @Transactional
    public GroundStaff update(Long id, GroundStaffDTO dto) {
        currentUserResolver.require();
        validate(dto);
        GroundStaff staff = staffRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("人员不存在: " + id));
        if (!staff.getStaffCode().equals(dto.getStaffCode().trim())
                && staffRepository.existsByStaffCode(dto.getStaffCode().trim())) {
            throw new IllegalArgumentException("人员编号已存在: " + dto.getStaffCode());
        }
        // 注意：改名只影响之后的新结论，已就绪值守的历史快照姓名不会被改动
        staff.setStaffCode(dto.getStaffCode().trim());
        staff.setStaffName(dto.getStaffName().trim());
        staff.setStaffRole(dto.getStaffRole());
        return staffRepository.save(staff);
    }

    @Transactional
    public void disable(Long id) {
        currentUserResolver.require();
        GroundStaff staff = staffRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("人员不存在: " + id));
        staff.setStatus(0);
        staffRepository.save(staff);
        log.info("停用地勤人员: {}({})", staff.getStaffName(), staff.getStaffCode());
    }

    public List<GroundStaff> findAll() {
        return staffRepository.findAll();
    }

    public List<GroundStaff> findActive() {
        return staffRepository.findByStatusOrderByIdAsc(1);
    }

    public GroundStaff getById(Long id) {
        return staffRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("人员不存在: " + id));
    }

    private void validate(GroundStaffDTO dto) {
        if (dto.getStaffCode() == null || dto.getStaffCode().isBlank()) {
            throw new IllegalArgumentException("人员编号不能为空");
        }
        if (dto.getStaffName() == null || dto.getStaffName().isBlank()) {
            throw new IllegalArgumentException("人员姓名不能为空");
        }
        if (!GroundStaff.ROLE_STATION_OFFICER.equals(dto.getStaffRole())
                && !GroundStaff.ROLE_SAFETY_OFFICER.equals(dto.getStaffRole())) {
            throw new IllegalArgumentException("人员角色必须是普通值班员或安全主管");
        }
    }
}
