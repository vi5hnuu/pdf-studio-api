package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.configuration.CreditProperties;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.GrantKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
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
        } catch (DataIntegrityViolationException | UnexpectedRollbackException
                 | TransientDataAccessException race) {
            // Another request from this IP got there first. That transaction is spent; a second
            // attempt now finds the row and takes the update path.
            //
            // TransientDataAccessException covers deadlock and lock-timeout, which the first two
            // do not: a deadlock arrives as CannotAcquireLockException and used to escape this
            // handler entirely, failing the whole request with a 500 on the balance endpoint.
            // The counter rewrite removed the deadlock, but a retry is the right answer to any
            // transient contention here, not just to the one shape we happened to hit.
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
                    digest.digest((salt + "|" + normalizeForBucket(ip)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Collapses an IPv6 address to its /64 prefix before bucketing.
     *
     * <p>Mobile carriers delegate a /64 or larger to each device, and clients can rotate through
     * the host portion of their delegation trivially — flipping that half of the address is
     * enough to look like a brand-new client on every request. Counting against the /64 keeps
     * the "one bucket per real user network" invariant. IPv4 is unchanged (every IPv4 address is
     * its own bucket already).
     *
     * <p>Malformed input is returned verbatim; the IP has already passed through
     * {@link com.vishnu.pdf_studio_api.pdfstudioapi.util.ClientIp}, which only yields what the
     * container peer or a trusted proxy supplied, so a nonsense value here is a bug upstream.
     */
    private String normalizeForBucket(String ip) {
        if (ip == null || ip.indexOf(':') < 0) return ip; // IPv4 or empty — leave alone
        try {
            byte[] addr = java.net.InetAddress.getByName(ip).getAddress();
            if (addr.length != 16) return ip; // IPv4-mapped shows up as 4 bytes; keep as-is
            byte[] prefix = new byte[16];
            System.arraycopy(addr, 0, prefix, 0, 8); // top /64, bottom bytes already zero
            return java.net.InetAddress.getByAddress(prefix).getHostAddress() + "/64";
        } catch (java.net.UnknownHostException unparseable) {
            return ip;
        }
    }
}
