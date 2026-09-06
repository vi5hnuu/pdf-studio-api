package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.enums.GrantKind;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.CreditGrantIp;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.CreditGrantIpRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
     * Increments today's counter for this IP and kind if the cap allows.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException if a concurrent request
     *         created today's row first; the caller retries, by which point the row exists
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean consume(String ipHash, GrantKind kind, LocalDate date, int cap) {
        var existing = repository.findForUpdate(ipHash, kind, date);
        if (existing.isPresent()) {
            CreditGrantIp counter = existing.get();
            if (counter.getGrantCount() >= cap) return false;
            counter.setGrantCount(counter.getGrantCount() + 1);
            repository.save(counter);
            return true;
        }
        repository.saveAndFlush(CreditGrantIp.builder()
                .ipHash(ipHash).grantKind(kind).grantDate(date).grantCount(1).build());
        return true;
    }
}
