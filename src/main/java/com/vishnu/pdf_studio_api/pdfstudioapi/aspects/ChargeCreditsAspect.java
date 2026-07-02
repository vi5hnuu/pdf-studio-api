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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;

/**
 * Enforces the credit price of {@link ChargeCredits}-annotated endpoints.
 *
 * <p>Flow: resolve the userId + input size (total uploaded bytes) → pre-check the balance
 * (fast 402 before any work) → run the tool → debit on success only. On a thrown exception
 * the tool "failed" and nothing is charged. A client-supplied {@code Idempotency-Key} makes
 * retries safe (the same run is never charged twice).
 */
@Aspect
@Component
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
        final int cost = creditsService.resolveCost(tool, sizeBytes);

        // Fast pre-check: reject before doing expensive work when clearly short on credits
        // (unless this exact run was already charged — an idempotent retry).
        if (cost > 0 && !creditsService.alreadyCharged(userId, idempotencyKey)
                && creditsService.getBalance(userId, ip) < cost) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "Not enough credits. This tool costs " + cost + " credit(s).");
        }

        final Object result = joinPoint.proceed(); // run the tool

        if (cost > 0) {
            // Debit atomically now that the tool succeeded; idempotent on retry.
            creditsService.charge(userId, tool, sizeBytes, idempotencyKey, ip);
        }
        return result;
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
