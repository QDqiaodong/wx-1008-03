package com.px.base.incident.repository;

import com.px.base.incident.entity.IncidentRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IncidentRevisionRepository extends JpaRepository<IncidentRevision, Long> {

    List<IncidentRevision> findByIncidentIdOrderByRevisionNoAsc(Long incidentId);

    Optional<IncidentRevision> findByIncidentIdAndRevisionNo(Long incidentId, Integer revisionNo);

    long countByIncidentId(Long incidentId);
}
