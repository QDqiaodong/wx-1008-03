package com.px.base.incident.repository;

import com.px.base.incident.entity.Incident;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IncidentRepository extends JpaRepository<Incident, Long> {

    Optional<Incident> findByIncidentNo(String incidentNo);

    boolean existsByIncidentNo(String incidentNo);

    long countByIncidentNoStartingWith(String prefix);

    /**
     * 写操作串行化：对事件行加悲观写锁。两个身份同时提交时，后到事务在锁上等待，
     * 拿到锁后读到的已是最新 version，再用 expectedVersion 做语义口径的冲突判定。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Incident i where i.id = :id")
    Optional<Incident> findByIdForUpdate(@Param("id") Long id);

    List<Incident> findAllByOrderByIdDesc();

    /**
     * 普通值班员可见范围：报告人、整改负责人，或该事件关联值守的操作员/复核员。
     * 关联值守参与身份通过 watch_id 命中 flight_watch 判定（一条 SQL 完成行级过滤，
     * 直接请求后端同样走此过滤，不依赖前端）。
     */
    @Query(value = """
            SELECT DISTINCT i.* FROM incident i
            LEFT JOIN flight_watch w ON w.id = i.watch_id
            WHERE i.reporter_id = :staffId
               OR i.owner_id = :staffId
               OR (w.id IS NOT NULL AND (w.operator_id = :staffId OR w.reviewer_id = :staffId))
            ORDER BY i.id DESC
            """, nativeQuery = true)
    List<Incident> findVisibleForStaff(@Param("staffId") Long staffId);
}
