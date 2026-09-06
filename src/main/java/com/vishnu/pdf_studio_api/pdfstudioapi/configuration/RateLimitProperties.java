package com.vishnu.pdf_studio_api.pdfstudioapi.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds {@code app.rate-limit.*}.
 *
 * <p>Credits already bound the paid tools, but roughly twenty tools are free (merge, split,
 * rotate, extract-text, analyze, sanitize…) and were previously unbounded — and several of those
 * rasterise every page, so a large document is cheap for the caller and expensive for us.
 *
 * <p>Two dimensions are limited independently: per authenticated user, and per client IP. The IP
 * bucket is the one that matters for the web tier, where a guest session costs nothing to mint.
 */
@Component
@ConfigurationProperties(prefix = "app.rate-limit")
@Getter
@Setter
public class RateLimitProperties {

    private boolean enabled = true;

    /** Tool calls per minute for one authenticated user. */
    private int userCapacity = 60;
    private int userRefillPerMinute = 60;

    /** Tool calls per minute from one IP, across every account behind it. */
    private int ipCapacity = 120;
    private int ipRefillPerMinute = 120;

    /** Tighter bucket for tools that rasterise or convert every page. */
    private int heavyCapacity = 12;
    private int heavyRefillPerMinute = 12;
}
