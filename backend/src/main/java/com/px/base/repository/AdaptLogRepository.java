package com.px.base.repository;

import com.px.base.entity.AdaptLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 适配流水仓储。
 *
 * 读取只暴露 JpaSpecificationExecutor（由 AdaptLogSnapshotService 统一带
 * 稳定排序与快照高水位）和快照高水位查询。不再提供无排序的 findByRouteId 等方法，
 * 防止航线筛选/全量/分页各走各的排序口径。
 */
@Repository
public interface AdaptLogRepository extends JpaRepository<AdaptLog, Long>, JpaSpecificationExecutor<AdaptLog> {

    /**
     * 快照高水位：当前库中最大的流水自增ID。
     * 读取/导出先取高水位，再只查 id <= 高水位 的记录，查询期间新增的流水
     * 不会混进本次快照，保证 total 与记录行数对应同一个查询时刻。
     */
    @Query("select max(l.id) from AdaptLog l")
    Optional<Long> findMaxId();
}
