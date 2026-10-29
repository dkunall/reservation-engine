package com.reservation.engine.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReservationAuditRepository extends JpaRepository<ReservationAudit, Long> {
    List<ReservationAudit> findByReservationIdOrderByIdAsc(UUID reservationId);
}
