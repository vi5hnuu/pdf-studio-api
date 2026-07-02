package com.vishnu.pdf_studio_api.pdfstudioapi.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a tool endpoint as credit-charged. The {@code ChargeCreditsAspect} resolves the
 * cost from the {@code tool_credit_costs} table for the given {@link #tool} id, pre-checks
 * the caller's balance (fast 402 before doing work), runs the tool, and debits on success
 * only. A cost of 0 (or a missing row) means no charge.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ChargeCredits {

    /** Tool id matching a row in {@code tool_credit_costs} (the endpoint path suffix). */
    String tool();
}
