package com.reservation.engine.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    List<PaymentTransaction> findByReservationIdOrderByCreatedAtAsc(UUID reservationId);

    @Query("""
            select p from PaymentTransaction p
             where p.reservationId = :reservationId and p.type = :type
               and p.status in (com.reservation.engine.payment.PaymentTransaction.Status.PENDING,
                                com.reservation.engine.payment.PaymentTransaction.Status.UNKNOWN)
             order by p.createdAt desc
            """)
    List<PaymentTransaction> findUnresolved(@Param("reservationId") UUID reservationId,
                                            @Param("type") PaymentTransaction.Type type);

    default Optional<PaymentTransaction> findLatestUnresolvedCharge(UUID reservationId) {
        return findUnresolved(reservationId, PaymentTransaction.Type.CHARGE).stream().findFirst();
    }

    @Query("""
            select p from PaymentTransaction p
             where p.itineraryId = :itineraryId
               and p.type = com.reservation.engine.payment.PaymentTransaction.Type.CHARGE
               and p.status in (com.reservation.engine.payment.PaymentTransaction.Status.PENDING,
                                com.reservation.engine.payment.PaymentTransaction.Status.UNKNOWN)
             order by p.createdAt desc
            """)
    List<PaymentTransaction> findUnresolvedItineraryCharges(@Param("itineraryId") UUID itineraryId);

    default Optional<PaymentTransaction> findLatestUnresolvedItineraryCharge(UUID itineraryId) {
        return findUnresolvedItineraryCharges(itineraryId).stream().findFirst();
    }

    List<PaymentTransaction> findByItineraryIdOrderByCreatedAtAsc(UUID itineraryId);

    @Query("""
            select p from PaymentTransaction p
             where p.type = com.reservation.engine.payment.PaymentTransaction.Type.REFUND
               and p.status in (com.reservation.engine.payment.PaymentTransaction.Status.PENDING,
                                com.reservation.engine.payment.PaymentTransaction.Status.UNKNOWN)
               and p.updatedAt <= :before
             order by p.updatedAt
            """)
    List<PaymentTransaction> findUnresolvedRefunds(@Param("before") Instant before,
                                                   org.springframework.data.domain.Pageable pageable);
}
