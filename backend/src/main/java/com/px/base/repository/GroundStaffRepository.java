package com.px.base.repository;

import com.px.base.entity.GroundStaff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GroundStaffRepository extends JpaRepository<GroundStaff, Long> {
    Optional<GroundStaff> findByStaffCode(String staffCode);
    boolean existsByStaffCode(String staffCode);
    List<GroundStaff> findByStatus(Integer status);
    List<GroundStaff> findByStatusOrderByIdAsc(Integer status);
}
