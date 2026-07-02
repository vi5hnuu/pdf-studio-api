package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.CreditProperties;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.CreditReason;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.CreditAccount;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.CreditLedger;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.CreditAccountRepository;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.CreditLedgerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bootstraps a user's {@link CreditAccount} the first time they're seen, granting the
 * one-time welcome credits. Kept separate from {@code CreditsService} so {@link #ensure}
 * can run in its own transaction ({@code REQUIRES_NEW}) — a first-request race that hits
 * the primary-key/idempotency constraint is isolated and cannot doom the caller's
 * balance-mutation transaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditAccountService {

    private final CreditAccountRepository accountRepository;
    private final CreditLedgerRepository ledgerRepository;
    private final CreditProperties creditProperties;

    /** Ensures the account exists (creating it + welcome grant if not), returning it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreditAccount ensure(String userId, String ip) {
        return accountRepository.findById(userId).orElseGet(() -> create(userId, ip));
    }

    private CreditAccount create(String userId, String ip) {
        final int welcome = creditProperties.getWelcomeGrant();
        try {
            CreditAccount account = accountRepository.save(CreditAccount.builder()
                    .userId(userId).balance(welcome).build());
            ledgerRepository.save(CreditLedger.builder()
                    .userId(userId).delta(welcome).reason(CreditReason.WELCOME)
                    .idempotencyKey("welcome:" + userId).balanceAfter(welcome).ip(ip).build());
            log.info("Created credit account with {} welcome credits: userId={}", welcome, userId);
            return account;
        } catch (DataIntegrityViolationException race) {
            // Concurrent first request already created it — use the winner's row.
            return accountRepository.findById(userId)
                    .orElseThrow(() -> race);
        }
    }
}
