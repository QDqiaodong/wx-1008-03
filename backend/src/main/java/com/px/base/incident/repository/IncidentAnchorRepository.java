package com.px.base.incident.repository;

import com.px.base.incident.entity.IncidentAnchor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IncidentAnchorRepository extends JpaRepository<IncidentAnchor, Long> {
    List<IncidentAnchor> findByIncidentIdOrderByIdAsc(Long incidentId);
}
