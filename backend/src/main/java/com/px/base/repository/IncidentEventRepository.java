package com.px.base.repository;

import com.px.base.entity.IncidentEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IncidentEventRepository extends JpaRepository<IncidentEvent, Long> {

    List<IncidentEvent> findAllByOrderByFoundTimeDescIdDesc();

    Optional<IncidentEvent> findByIncidentCode(String incidentCode);

    boolean existsByIncidentCode(String incidentCode);

    /**
     * 整份事件版本冲突口径的关键：写操作前对事件行加悲观写锁，串行化并发提交。
     * 第二个提交者会阻塞到第一个提交完成，随后在同一事务内按最新 version 比对，
     * 旧版本确认变更 → 409（不会在锁保护下发生丢失更新）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from IncidentEvent e where e.id = :id")
    Optional<IncidentEvent> findByIdForUpdate(@Param("id") Long id);
}
