package com.px.base.service;

import com.px.base.dto.AdaptLogFilter;
import com.px.base.dto.AdaptLogPageDTO;
import com.px.base.entity.AdaptLog;
import com.px.base.entity.FlightRoute;
import com.px.base.repository.AdaptLogRepository;
import com.px.base.repository.FlightRouteRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 适配流水快照读取。
 *
 * 航线筛选、全量查询、分页查询、导出全部走这里，共用：
 *  1. 同一份筛选条件（AdaptLogFilter）；
 *  2. 同一套稳定排序：create_time DESC, id DESC（发生时间倒序，同一时刻用自增ID打破并列，
 *     拒绝记录刷新后不再前后跳动）；
 *  3. 同一个查询时刻：先取 max(id) 冻结快照高水位，再查 id <= 高水位 的记录，
 *     查询/导出期间新写入的流水不会混进来，total 与行数必然对得上。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdaptLogSnapshotService {

    /** 稳定排序：先按发生时间倒序，时间相同按自增ID倒序——并列也有确定顺序 */
    public static final Sort STABLE_SORT = Sort.by(
            Sort.Order.desc("createTime"),
            Sort.Order.desc("id"));

    public static final String SORT_DESC = "create_time DESC, id DESC";

    private final AdaptLogRepository adaptLogRepository;
    private final FlightRouteRepository flightRouteRepository;

    /**
     * 分页快照。total、页码、记录顺序对应同一个冻结时刻。
     */
    @Transactional(readOnly = true)
    public AdaptLogPageDTO page(AdaptLogFilter filter, int requestedPageNo, int requestedPageSize) {
        int pageSize = requestedPageSize <= 0 ? 20 : Math.min(requestedPageSize, 500);

        SnapshotBound bound = freeze(filter);

        Specification<AdaptLog> spec = withinSnapshot(filter, bound.maxId());
        Pageable pageable = PageRequest.of(0, pageSize, STABLE_SORT);
        Page<AdaptLog> first = adaptLogRepository.findAll(spec, pageable);
        long total = first.getTotalElements();
        int totalPages = first.getTotalPages();

        int pageNo = requestedPageNo <= 0 ? 1 : requestedPageNo;
        if (totalPages > 0 && pageNo > totalPages) {
            // 越界页码（例如删掉了数据）不抛错，落到最后一页，保证页面始终有对应数据
            pageNo = totalPages;
        }

        Page<AdaptLog> data = pageNo == 1
                ? first
                : adaptLogRepository.findAll(spec, PageRequest.of(pageNo - 1, pageSize, STABLE_SORT));

        return AdaptLogPageDTO.builder()
                .filter(filter)
                .routeCode(bound.routeCode())
                .snapshotMaxId(bound.maxId())
                .snapshotTime(bound.snapshotTime())
                .total(total)
                .pageNo(pageNo)
                .pageSize(pageSize)
                .totalPages(totalPages)
                .sort(SORT_DESC)
                .records(data.getContent())
                .build();
    }

    /**
     * 全量快照（导出用）。在同一个只读事务里先冻结高水位再取数，
     * 与分页接口共用筛选与排序，返回的行数与 total 一致。
     */
    @Transactional(readOnly = true)
    public AdaptLogPageDTO fullSnapshot(AdaptLogFilter filter) {
        SnapshotBound bound = freeze(filter);
        Specification<AdaptLog> spec = withinSnapshot(filter, bound.maxId());
        List<AdaptLog> records = adaptLogRepository.findAll(spec, STABLE_SORT);
        return AdaptLogPageDTO.builder()
                .filter(filter)
                .routeCode(bound.routeCode())
                .snapshotMaxId(bound.maxId())
                .snapshotTime(bound.snapshotTime())
                .total(records.size())
                .pageNo(1)
                .pageSize(records.size())
                .totalPages(1)
                .sort(SORT_DESC)
                .records(records)
                .build();
    }

    /**
     * 冻结快照边界：记录当前最大流水ID与服务端时刻。
     * 之后写入的流水 id 更大，被天然排除在本次快照之外。
     */
    private SnapshotBound freeze(AdaptLogFilter filter) {
        Long maxId = adaptLogRepository.findMaxId().orElse(0L);
        LocalDateTime snapshotTime = LocalDateTime.now();
        String routeCode = null;
        if (filter != null && filter.getRouteId() != null) {
            Optional<FlightRoute> route = flightRouteRepository.findById(filter.getRouteId());
            if (route.isEmpty()) {
                // 筛选了不存在的航线：明确按空结果处理，而不是悄悄退化成“全部航线”
                throw new IllegalArgumentException("筛选航线不存在: " + filter.getRouteId());
            }
            routeCode = route.get().getRouteCode();
        }
        return new SnapshotBound(maxId, snapshotTime, routeCode);
    }

    private Specification<AdaptLog> withinSnapshot(AdaptLogFilter filter, Long maxId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.le(root.get("id"), maxId));
            if (filter != null && filter.getRouteId() != null) {
                predicates.add(cb.equal(root.get("routeId"), filter.getRouteId()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private record SnapshotBound(Long maxId, LocalDateTime snapshotTime, String routeCode) {
    }
}
