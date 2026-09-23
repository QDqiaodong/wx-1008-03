package com.px.base.repository;

import com.px.base.entity.IncidentSealRound;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IncidentSealRoundRepository extends JpaRepository<IncidentSealRound, Long> {

    List<IncidentSealRound> findByIncidentIdOrderByRoundNoAsc(Long incidentId);

    /** 当前封存轮次（含已重开的历史轮次）数量；下一轮次号 = count + 1 */
    int countByIncidentId(Long incidentId);
}
