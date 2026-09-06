package com.vishnu.pdf_studio_api.pdfstudioapi.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint whose work is CPU- and memory-intensive enough that concurrent executions must
 * be bounded. {@code HeavyToolAspect} admits at most
 * {@code app.load.max-concurrent-heavy} of these at a time; the rest queue briefly and then get a
 * 503 with {@code Retry-After} rather than everyone competing for a heap that cannot hold them all.
 *
 * <p>Applied to the rasterising and converting tools — the ones whose peak footprint scales with
 * page count rather than file size.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface HeavyTool {
}
