package com.vishnu.pdf_studio_api.pdfstudioapi.configuration;

import com.vishnu.pdf_studio_api.pdfstudioapi.repository.CreditGrantIpRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Deletes per-IP grant counters once their day has passed.
 *
 * <p>The counters only ever matter for the current UTC day, but nothing removed them — the
 * repository had a delete method that no caller used, so the table grew by one row per
 * distinct IP per grant kind per day, indefinitely. A few days are kept for support
 * questions about a refused grant.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CreditGrantIpCleanupJob {

    /** Days of history retained beyond today. */
    private static final int RETAIN_DAYS = 3;

    private final CreditGrantIpRepository repository;

    @Scheduled(cron = "${app.credits.ip-cleanup-cron:0 15 3 * * *}")
    @Transactional
    public void purgeExpiredCounters() {
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(RETAIN_DAYS);
        repository.deleteByGrantDateBefore(cutoff);
        log.info("Purged per-IP grant counters older than {}", cutoff);
    }
}
