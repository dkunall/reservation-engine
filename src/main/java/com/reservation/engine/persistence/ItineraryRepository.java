package com.reservation.engine.persistence;

import com.reservation.engine.domain.Itinerary;
import com.reservation.engine.domain.ReservationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ItineraryRepository extends JpaRepository<Itinerary, UUID> {

    /** First lock of every itinerary command; items are locked afterwards in id order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Itinerary i where i.id = :id")
    Optional<Itinerary> findByIdForUpdate(@Param("id") UUID id);

    Page<Itinerary> findByUserId(String userId, Pageable pageable);

    Page<Itinerary> findByUserIdAndStatus(String userId, ReservationStatus status, Pageable pageable);

    @Query(value = """
            select * from itinerary
             where status = 'HELD' and hold_expires_at <= :now
             order by hold_expires_at
             limit :limit
             for update skip locked
            """, nativeQuery = true)
    List<Itinerary> lockExpiredHolds(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = """
            select id from itinerary
             where status = 'PAYMENT_PENDING' and updated_at <= :before
             order by updated_at
             limit :limit
            """, nativeQuery = true)
    List<UUID> findStalePaymentPending(@Param("before") Instant before, @Param("limit") int limit);
}
