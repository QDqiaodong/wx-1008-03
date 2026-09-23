package com.px.base.repository;

import com.px.base.entity.GroundCert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GroundCertRepository extends JpaRepository<GroundCert, Long> {
    Optional<GroundCert> findByCertNo(String certNo);
    boolean existsByCertNo(String certNo);
    List<GroundCert> findByStaffId(Long staffId);
    List<GroundCert> findByStaffIdOrderByExpiryDateDesc(Long staffId);
    List<GroundCert> findByStaffIdAndRevoked(Long staffId, Integer revoked);
}
