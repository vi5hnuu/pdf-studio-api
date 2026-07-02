package com.vishnu.pdf_studio_api.pdfstudioapi.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Binds {@code app.credits.*} — the free-credit economy knobs. */
@Component
@ConfigurationProperties(prefix = "app.credits")
@Getter
@Setter
public class CreditProperties {

    /** Credits granted once, the first time a user is seen. */
    private int welcomeGrant = 10;
    /** Credits granted per daily-allowance claim. */
    private int dailyAllowance = 3;
    /** Credits granted per rewarded-ad view. */
    private int rewardedAdGrant = 2;
    /** Max rewarded-ad credits a user can earn per calendar day. */
    private int rewardedAdDailyCap = 10;
}
