package com.vishnu.pdf_studio_api.pdfstudioapi.repository;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.GrantKind;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.CreditGrantIp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;

@Repository
public interface CreditGrantIpRepository extends JpaRepository<CreditGrantIp, Long> {

    /**
     * Counts one grant attempt for this IP, creating today's counter if it does not exist.
     *
     * <p>One statement, because every two-statement shape deadlocks here. The original was
     * {@code SELECT ... FOR UPDATE} then {@code INSERT}: on a row that does not exist the select
     * takes a <em>gap</em> lock, gap locks are shared, so two requests from one IP each took one
     * and each then blocked on the other's insert. That surfaced in production as
     * {@code Deadlock found when trying to get lock} and a 500 on the balance endpoint.
     *
     * <p>Verified against MySQL 9.1 rather than reasoned about: a leading {@code UPDATE} deadlocks
     * the same way, because an update matching no rows also takes a gap lock, and
     * {@code INSERT IGNORE} followed by an update deadlocks on the shared-to-exclusive upgrade.
     * The upsert is the only form that four concurrent writers survive.
     *
     * <p>Deliberately unconditional: making the increment conditional on the cap would mean
     * reading the affected-row count to tell "incremented" from "already capped", and that count
     * means different things depending on the driver's {@code CLIENT_FOUND_ROWS} setting. Letting
     * the counter run past the cap and comparing the value instead is exact, and the overshoot is
     * harmless — the row is a daily tally that only ever gates a boolean, and is deleted nightly.
     */
    @Modifying
    @Query(value = """
           INSERT INTO credit_ip_grants (ip_hash, grant_kind, grant_date, grant_count, created_at)
           VALUES (:ipHash, :kind, :date, 1, :now)
           ON DUPLICATE KEY UPDATE grant_count = grant_count + 1
           """, nativeQuery = true)
    void countOneAttempt(@Param("ipHash") String ipHash,
                         @Param("kind") String kind,
                         @Param("date") LocalDate date,
                         @Param("now") Instant now);

    /** Today's tally for this IP and kind, read after {@link #countOneAttempt}. */
    @Query("""
           SELECT g.grantCount FROM CreditGrantIp g
           WHERE g.ipHash = :ipHash AND g.grantKind = :kind AND g.grantDate = :date
           """)
    Integer currentCount(@Param("ipHash") String ipHash,
                         @Param("kind") GrantKind kind,
                         @Param("date") LocalDate date);

    /** Housekeeping: the counters only matter for the current day. */
    void deleteByGrantDateBefore(LocalDate date);
}
