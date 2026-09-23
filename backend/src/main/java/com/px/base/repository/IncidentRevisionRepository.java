package com.px.base.repository;

import com.px.base.entity.IncidentRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IncidentRevisionRepository extends JpaRepository<IncidentRevision, Long> {

    /** 修订历史严格按 seq 升序返回，seq 在事件内连续无重复 */
    List<IncidentRevision> findByIncidentIdOrderBySeqAsc(Long incidentId);

    int countByIncidentId(Long incidentId);
}
