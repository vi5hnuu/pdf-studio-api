package com.vishnu.pdf_studio_api.pdfstudioapi.configuration;

import com.vishnu.pdf_studio_api.pdfstudioapi.model.AppReleaseConfig;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.AppReleaseConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds a release-config row per platform, only when one is absent — so an edit made in the
 * database survives a restart, exactly as {@link ToolCreditCostSeeder} does for tool prices.
 *
 * <p>Seeded minimums are zero. Shipping this must change nothing for anyone; the gate only ever
 * closes because someone deliberately raised the number.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AppReleaseConfigSeeder implements ApplicationRunner {

    private final AppReleaseConfigRepository repository;

    @Value("${app.play.package-name:com.vi5hnu.pdf_craft}")
    private String packageName;

    @Override
    public void run(ApplicationArguments args) {
        seed("ANDROID", "https://play.google.com/store/apps/details?id=" + packageName);
        seed("IOS", null); // the shape exists; nothing ships there yet
    }

    private void seed(String platform, String storeUrl) {
        if (repository.existsById(platform)) return;
        repository.save(AppReleaseConfig.builder()
                .platform(platform)
                .minBuild(0)
                .recommendedBuild(0)
                .storeUrl(storeUrl)
                .ratingMinSuccesses(3)
                .ratingMinDaysInstalled(3)
                .ratingMinDaysBetweenAsks(60)
                .build());
        log.info("Seeded default release config for {}", platform);
    }
}
