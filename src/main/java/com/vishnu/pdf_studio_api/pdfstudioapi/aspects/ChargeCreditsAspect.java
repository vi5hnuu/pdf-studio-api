package com.vishnu.pdf_studio_api.pdfstudioapi.aspects;

import com.vishnu.pdf_studio_api.pdfstudioapi.annotation.ChargeCredits;
import com.vishnu.pdf_studio_api.pdfstudioapi.security.CurrentUser;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.CreditsService;
import com.vishnu.pdf_studio_api.pdfstudioapi.util.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.OptionalInt;

/**
 * Enforces the credit price of {@link ChargeCredits}-annotated endpoints.
 *
 * <p>Flow: resolve the userId and input size → resolve the price <b>once</b> → fast pre-check the
 * balance (so an out-of-credit caller is refused before any expensive work) → run the tool → debit
 * on success only. A thrown exception means the tool failed and nothing is charged.
 *
 * <p>The debit lands when the tool completes, not when the download finishes. That is deliberate:
 * the server has already spent the CPU by then, and deferring the write until after the body is
 * streamed would mean a failed debit could not be recovered — the user would have received the work
 * for free with no record. Retries are instead made safe by {@code Idempotency-Key}, which the
 * ledger enforces with a unique constraint, so a client that resends a request it never saw the
 * response to is charged exactly once.
 */
@Aspect
@Component
@Order(1) // after ValidateUploadAspect — never charge for an upload we reject
@RequiredArgsConstructor
public class ChargeCreditsAspect {

    private final CreditsService creditsService;

    @Around("@annotation(chargeCredits)")
    public Object around(ProceedingJoinPoint joinPoint, ChargeCredits chargeCredits) throws Throwable {
        final String tool = chargeCredits.tool();
        final String userId = CurrentUser.requireId();

        final HttpServletRequest request = currentRequest();
        final String ip = request != null ? ClientIp.of(request) : null;
        final String idempotencyKey = request != null ? request.getHeader("Idempotency-Key") : null;

        final long sizeBytes = totalUploadedBytes(joinPoint.getArgs());
        // Resolved once and reused for both the pre-check and the debit, so the caller can never be
        // quoted one price and charged another (and the cost table is read once, not twice).
        final int cost = creditsService.resolveCost(tool, sizeBytes);

        final boolean alreadyCharged = creditsService.alreadyCharged(userId, idempotencyKey);
        if (cost > 0 && !alreadyCharged) {
            // Fast pre-check. Deliberately does not create the account: a first-time user has no
            // row yet, and charge() ensures it under lock anyway. The authoritative check is there.
            OptionalInt balance = creditsService.peekBalance(userId);
            if (balance.isPresent() && balance.getAsInt() < cost) {
                throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                        "Not enough credits. This tool costs " + cost + " credit(s); you have "
                                + balance.getAsInt() + ".");
            }
        }

        final Object result = joinPoint.proceed(); // run the tool

        if (cost > 0) {
            var charge = creditsService.charge(userId, tool, cost, idempotencyKey, ip);
            return withCreditHeaders(result, charge);
        }
        return result;
    }

    /**
     * Echoes what was charged and what remains, so a client can reconcile its local balance from the
     * response it already has instead of issuing a follow-up balance request after every tool run.
     */
    private Object withCreditHeaders(Object result, CreditsService.ChargeResult charge) {
        if (!(result instanceof ResponseEntity<?> response)) return result;
        return ResponseEntity.status(response.getStatusCode())
                .headers(headers -> {
                    headers.addAll(response.getHeaders());
                    headers.add("X-Credits-Charged", String.valueOf(charge.charged()));
                    headers.add("X-Credits-Remaining", String.valueOf(charge.balanceRemaining()));
                })
                .body(response.getBody());
    }

    /** Sums the byte size of every MultipartFile (or collection of them) in the args. */
    private long totalUploadedBytes(Object[] args) {
        long total = 0;
        for (Object arg : args) {
            if (arg instanceof MultipartFile mf) {
                total += mf.getSize();
            } else if (arg instanceof Collection<?> coll) {
                for (Object o : coll) {
                    if (o instanceof MultipartFile mf) total += mf.getSize();
                }
            }
        }
        return total;
    }

    private HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }
}
