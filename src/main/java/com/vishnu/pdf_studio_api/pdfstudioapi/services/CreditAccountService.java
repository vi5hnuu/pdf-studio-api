package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.model.CreditAccount;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.CreditAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.UnexpectedRollbackException;

/**
 * Bootstraps a user's {@link CreditAccount} the first time they are seen.
 *
 * <p>Not transactional itself: creation runs in its own transaction inside
 * {@link CreditAccountCreator}, so a first-request race that hits the primary key is isolated and
 * cannot doom the caller's balance-mutation transaction — and can actually be recovered from here,
 * which is impossible from inside the failing transaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditAccountService {

    private final CreditAccountRepository accountRepository;
    private final CreditAccountCreator creator;

    /** Ensures the account exists (creating it + welcome grant if not), returning it. */
    public CreditAccount ensure(String userId, String ip) {
        return accountRepository.findById(userId).orElseGet(() -> create(userId, ip));
    }

    private CreditAccount create(String userId, String ip) {
        try {
            return creator.create(userId, ip);
        } catch (DataIntegrityViolationException | UnexpectedRollbackException race) {
            // A concurrent first request won; use the winner's row rather than failing the caller.
            return accountRepository.findById(userId)
                    .orElseThrow(() -> race);
        }
    }
}
