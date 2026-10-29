package com.reservation.engine.waitlist;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WaitlistRepository extends JpaRepository<WaitlistEntry, UUID> {

    /** FIFO candidates (unlocked read); each is then claimed individually with {@link #lockIfWaiting}. */
    @Query(value = """
            select id from waitlist_entry
             where resource_id = :resourceId and status = 'WAITING' and expires_at > :now
             order by requested_at, id
             limit :limit
            """, nativeQuery = true)
    List<UUID> findWaitingFifo(@Param("resourceId") UUID resourceId, @Param("now") Instant now,
                               @Param("limit") int limit);

    /**
     * Claims one entry for promotion. SKIP LOCKED: if another promoter is working on it we move on instead of
     * waiting; the status predicate makes an already-promoted entry invisible (exactly-once).
     */
    @Query(value = """
            select * from waitlist_entry
             where id = :id and status = 'WAITING'
             for update skip locked
            """, nativeQuery = true)
    Optional<WaitlistEntry> lockIfWaiting(@Param("id") UUID id);

    @Query(value = "select * from waitlist_entry where id = :id for update", nativeQuery = true)
    Optional<WaitlistEntry> findByIdForUpdate(@Param("id") UUID id);

    Page<WaitlistEntry> findByUserId(String userId, Pageable pageable);

    Page<WaitlistEntry> findByUserIdAndStatus(String userId, WaitlistStatus status, Pageable pageable);

    @Query(value = """
            select * from waitlist_entry
             where status = 'WAITING' and expires_at <= :now
             order by expires_at
             limit :limit
             for update skip locked
            """, nativeQuery = true)
    List<WaitlistEntry> lockExpired(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = "select distinct resource_id from waitlist_entry where status = 'WAITING'", nativeQuery = true)
    List<UUID> findResourcesWithWaitingEntries();
}
