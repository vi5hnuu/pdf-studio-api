package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.GrantKind;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.CreditGrantIpRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The transactional half of the per-IP grant cap.
 *
 * <p>Deliberately a separate bean from {@link IpGrantGuard}. The insert races with other requests
 * from the same IP, and a constraint violation poisons the transaction it happens in — catching it
 * there and carrying on still fails at commit with {@code UnexpectedRollbackException}. Recovery
 * therefore has to run in a <em>new</em> transaction, which means crossing a proxy boundary, which
 * means a second bean. A self-call would silently run without a transaction at all.
 */
@Service
@RequiredArgsConstructor
public class CreditGrantIpCounter {

    private final CreditGrantIpRepository repository;

    /**
     * Counts one grant attempt for this IP today and says whether it is within the cap.
     *
     * <p>The Nth caller on a given IP sees N, so exactly the first {@code cap} callers are
     * allowed. Counting and reading are separate statements but both run inside this
     * transaction, and the counting statement is itself atomic, so two callers can never be
     * handed the same number.
     *
     * @return true when the grant may proceed, false when this IP is already at its cap
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean consume(String ipHash, GrantKind kind, LocalDate date, int cap) {
        repository.countOneAttempt(ipHash, kind.name(), date, Instant.now());
        Integer used = repository.currentCount(ipHash, kind, date);
        // A missing row here would mean the upsert did not take; allow rather than deny, which
        // is how every other failure on this path behaves — bookkeeping must not cost a user
        // their credits.
        return used == null || used <= cap;
    }
}
