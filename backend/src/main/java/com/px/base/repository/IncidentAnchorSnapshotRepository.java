package com.px.base.repository;

import com.px.base.entity.IncidentAnchorSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IncidentAnchorSnapshotRepository extends JpaRepository<IncidentAnchorSnapshot, Long> {
    List<IncidentAnchorSnapshot> findByIncidentIdOrderBySortNoAscIdAsc(Long incidentId);
}
