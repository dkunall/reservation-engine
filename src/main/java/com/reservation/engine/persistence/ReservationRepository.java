package com.reservation.engine.persistence;

import com.reservation.engine.domain.Reservation;
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

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    /** Row lock (SELECT ... FOR UPDATE) used by every state-changing command. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") UUID id);

    /** Single query including the resource (no lazy load / N+1 when rendering). */
    @Query("select r from Reservation r join fetch r.resource where r.id = :id")
    Optional<Reservation> findWithResourceById(@Param("id") UUID id);

    @Query(value = "select r from Reservation r join fetch r.resource where r.userId = :userId",
            countQuery = "select count(r) from Reservation r where r.userId = :userId")
    Page<Reservation> findByUserIdWithResource(@Param("userId") String userId, Pageable pageable);

    @Query(value = "select r from Reservation r join fetch r.resource where r.userId = :userId and r.status = :status",
            countQuery = "select count(r) from Reservation r where r.userId = :userId and r.status = :status")
    Page<Reservation> findByUserIdAndStatusWithResource(@Param("userId") String userId,
                                                        @Param("status") ReservationStatus status,
                                                        Pageable pageable);

    /**
     * Claims a batch of expired holds. SKIP LOCKED lets several sweeper instances run in parallel and never
     * blocks on (or steals) a reservation that a confirm/cancel request has locked.
     */
    @Query(value = """
            select * from reservation
             where status = 'HELD' and hold_expires_at <= :now and itinerary_id is null
             order by hold_expires_at
             limit :limit
             for update skip locked
            """, nativeQuery = true)
    List<Reservation> lockExpiredHolds(@Param("now") Instant now, @Param("limit") int limit);

    /** Items of an itinerary, row-locked in id order (the global lock order: itinerary row first, then items). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.itineraryId = :itineraryId order by r.id")
    List<Reservation> findByItineraryIdForUpdate(@Param("itineraryId") UUID itineraryId);

    @Query("select r from Reservation r join fetch r.resource where r.itineraryId = :itineraryId order by r.createdAt, r.id")
    List<Reservation> findByItineraryIdWithResource(@Param("itineraryId") UUID itineraryId);

    @Query(value = """
            select id from reservation
             where status = 'PAYMENT_PENDING' and updated_at <= :before and itinerary_id is null
             order by updated_at
             limit :limit
            """, nativeQuery = true)
    List<UUID> findStalePaymentPending(@Param("before") Instant before, @Param("limit") int limit);
}
