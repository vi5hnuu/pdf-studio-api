package com.vishnu.pdf_studio_api.pdfstudioapi.aspects;

import com.vishnu.pdf_studio_api.pdfstudioapi.annotation.HeavyTool;
import com.vishnu.pdf_studio_api.pdfstudioapi.concurrency.HeavyToolLimiter;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Applies {@link HeavyToolLimiter} to {@link HeavyTool}-annotated endpoints.
 *
 * <p>Runs innermost, inside {@code ChargeCreditsAspect}, so a caller who is out of credits gets an
 * immediate 402 rather than waiting out the admission queue only to be refused anyway. Shedding
 * still costs them nothing: the 503 propagates out through the credit aspect's {@code proceed()},
 * which debits only on a successful return.
 */
@Aspect
@Component
@Order(2) // innermost: ValidateUpload(0) -> ChargeCredits(1) -> HeavyTool(2)
@RequiredArgsConstructor
public class HeavyToolAspect {

    private final HeavyToolLimiter limiter;

    @Around("@annotation(com.vishnu.pdf_studio_api.pdfstudioapi.annotation.HeavyTool)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        return limiter.call(joinPoint::proceed);
    }
}
