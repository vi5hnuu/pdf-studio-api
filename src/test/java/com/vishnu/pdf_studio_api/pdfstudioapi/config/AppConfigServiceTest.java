package com.vishnu.pdf_studio_api.pdfstudioapi.config;

import com.vishnu.pdf_studio_api.pdfstudioapi.model.AppReleaseConfig;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.AppReleaseConfigRepository;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.AppConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The update gate is the one feature here that can lock every install out of the app at once, so
 * these are mostly about it refusing to do that by accident.
 */
@SpringBootTest
class AppConfigServiceTest {

    @Autowired AppConfigService appConfigService;
    @Autowired AppReleaseConfigRepository repository;

    private void configure(String platform, int minBuild, int recommendedBuild) {
        repository.save(AppReleaseConfig.builder()
                .platform(platform)
                .minBuild(minBuild)
                .recommendedBuild(recommendedBuild)
                .ratingMinSuccesses(3)
                .ratingMinDaysInstalled(3)
                .ratingMinDaysBetweenAsks(60)
                .build());
    }

    @Test
    @DisplayName("a build below the minimum is gated, and one equal to it is not")
    void gatesOnlyBelowTheMinimum() {
        configure("TESTA", 21, 25);

        assertTrue(appConfigService.forClient("TESTA", 20).updateRequired());
        // The boundary is the whole feature: off by one here locks out the build you just shipped.
        assertFalse(appConfigService.forClient("TESTA", 21).updateRequired(),
                "the minimum supported build must itself be supported");
        assertFalse(appConfigService.forClient("TESTA", 99).updateRequired());
    }

    @Test
    @DisplayName("between the minimum and the recommended build, the app is nudged, not blocked")
    void nudgesBetweenTheThresholds() {
        configure("TESTB", 21, 25);

        var view = appConfigService.forClient("TESTB", 22);
        assertFalse(view.updateRequired());
        assertTrue(view.updateRecommended());

        // Being blocked already implies being out of date; reporting both would have the app
        // show a dismissible nudge on top of an unskippable gate.
        var blocked = appConfigService.forClient("TESTB", 10);
        assertTrue(blocked.updateRequired());
        assertFalse(blocked.updateRecommended());
    }

    @Test
    @DisplayName("nothing configured enforces nothing")
    void failsOpen() {
        // An unknown platform, a missing row, a client that did not report its build. None of
        // these is an edge case worth failing on: each would otherwise be a way to lock users out
        // of the app that no release could save them from.
        assertFalse(appConfigService.forClient("NOT-A-PLATFORM", 1).updateRequired());
        assertFalse(appConfigService.forClient(null, 1).updateRequired());
        assertFalse(appConfigService.forClient("", 1).updateRequired());

        configure("TESTC", 999, 999);
        assertFalse(appConfigService.forClient("TESTC", -1).updateRequired(),
                "a client that could not read its own build must not be gated");
    }

    @Test
    @DisplayName("the platform is matched case-insensitively")
    void platformIsCaseInsensitive() {
        configure("TESTD", 50, 50);
        assertTrue(appConfigService.forClient("testd", 1).updateRequired());
    }
}
