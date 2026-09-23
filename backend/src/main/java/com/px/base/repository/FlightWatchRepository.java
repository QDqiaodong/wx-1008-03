package com.px.base.repository;

import com.px.base.entity.FlightWatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface FlightWatchRepository extends JpaRepository<FlightWatch, Long> {

    List<FlightWatch> findAllByOrderByFlightDateDescIdDesc();

    List<FlightWatch> findByRouteIdOrderByFlightDateDescIdDesc(Long routeId);

    List<FlightWatch> findByFlightDateOrderByIdAsc(LocalDate flightDate);

    List<FlightWatch> findByOperatorIdOrReviewerId(Long operatorId, Long reviewerId);

    /** 证书吊销后立即重新判定：只动“未来飞行日且尚未就绪”的安排（CANCELLED 终态也不动） */
    List<FlightWatch> findByFlightDateAfterAndStatusIn(LocalDate date, List<String> statuses);

    /** 同航线 + 飞行日不允许重复排班（取消的历史记录除外，允许重新安排） */
    List<FlightWatch> findByRouteIdAndFlightDateAndStatusNot(Long routeId, LocalDate flightDate, String status);
}
