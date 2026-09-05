package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.CreditProperties;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.CreditReason;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.GrantKind;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.CreditAccount;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.CreditLedger;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.CreditAccountRepository;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.CreditLedgerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a user's credit account and applies the one-time welcome grant.
 *
 * <p>Split from {@link CreditAccountService} so the primary-key race on a user's very first
 * concurrent requests can be recovered from. A constraint violation marks its transaction
 * rollback-only, so catching it inside the same transaction still fails at commit — the caller has
 * to catch it from outside, which requires this to be a separate proxied bean.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditAccountCreator {

    private final CreditAccountRepository accountRepository;
    private final CreditLedgerRepository ledgerRepository;
    private final CreditProperties creditProperties;
    private final IpGrantGuard ipGrantGuard;

    /**
     * @throws org.springframework.dao.DataIntegrityViolationException if the account already exists
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreditAccount create(String userId, String ip) {
        // Web guest accounts cost nothing to mint, so the welcome grant is rationed per IP as well
        // as per account. A refused grant still creates the account — just with no opening balance.
        final boolean grantAllowed = ipGrantGuard.tryConsume(ip, GrantKind.WELCOME);
        final int welcome = grantAllowed ? creditProperties.getWelcomeGrant() : 0;

        CreditAccount account = accountRepository.saveAndFlush(CreditAccount.builder()
                .userId(userId).balance(welcome).build());
        if (welcome > 0) {
            ledgerRepository.save(CreditLedger.builder()
                    .userId(userId).delta(welcome).reason(CreditReason.WELCOME)
                    .idempotencyKey("welcome:" + userId).balanceAfter(welcome).ip(ip).build());
        }
        log.info("Created credit account with {} welcome credits: userId={}", welcome, userId);
        return account;
    }
}
