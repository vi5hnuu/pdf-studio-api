package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.CreditProperties;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.GrantKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.UnexpectedRollbackException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;

/**
 * Rations free credit grants per client IP, on top of the existing per-account limits.
 *
 * <p>Per-account limits assume accounts are costly to obtain. That holds for the mobile app, but the
 * web tier mints a guest account on demand — so clearing site data and reloading would yield an
 * endless series of welcome balances. Counting grants per IP per day closes that. The caps are
 * daily and generous, so a shared office, campus or mobile-carrier address is not locked out.
 *
 * <p>Not itself transactional: the retry below must start a <em>fresh</em> transaction, which is
 * only possible from outside one. See {@link CreditGrantIpCounter}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IpGrantGuard {

    private final CreditGrantIpCounter counter;
    private final CreditProperties creditProperties;

    /**
     * Salt so the stored hashes are not simply a rainbow table of the IPv4 space — only ~4 billion
     * values, so an unsalted hash of an IP address is trivially reversible.
     */
    @Value("${app.credits.ip-hash-salt:pdf-studio-default-salt}")
    private String salt;

    /**
     * Records one grant of {@code kind} for {@code ip} if today's cap allows it.
     *
     * @return true when the grant may proceed; false when this IP has had its share today
     */
    public boolean tryConsume(String ip, GrantKind kind) {
        final int cap = capFor(kind);
        if (cap <= 0) return true;                   // cap disabled
        if (ip == null || ip.isBlank()) return true; // nothing to attribute it to; account limits still apply

        final String ipHash = hash(ip);
        final LocalDate today = LocalDate.now(ZoneOffset.UTC);
        try {
            return counter.consume(ipHash, kind, today, cap);
        } catch (DataIntegrityViolationException | UnexpectedRollbackException race) {
            // Another request from this IP created today's row first. That transaction is spent;
            // a second attempt now finds the row and takes the locking path.
            try {
                return counter.consume(ipHash, kind, today, cap);
            } catch (RuntimeException stillFailing) {
                // Never let bookkeeping deny a legitimate user their credits.
                log.warn("IP grant counter unavailable, allowing the grant: {}", stillFailing.getMessage());
                return true;
            }
        }
    }

    private int capFor(GrantKind kind) {
        return switch (kind) {
            case WELCOME -> creditProperties.getWelcomeGrantsPerIpPerDay();
            case DAILY -> creditProperties.getDailyGrantsPerIpPerDay();
        };
    }

    /** Salted SHA-256, so the table can be counted against but not read back as addresses. */
    private String hash(String ip) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest((salt + "|" + ip).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
