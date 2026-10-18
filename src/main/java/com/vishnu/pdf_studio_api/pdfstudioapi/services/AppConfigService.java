package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.vishnu.pdf_studio_api.pdfstudioapi.model.AppReleaseConfig;
import com.vishnu.pdf_studio_api.pdfstudioapi.repository.AppReleaseConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers "is this build still supported, and may I ask this user for a rating?".
 *
 * <p>The comparison lives here rather than in the app so the rule can change without a release —
 * which matters, because the app this gates is by definition the one that cannot be changed
 * quickly.
 */
@Service
@RequiredArgsConstructor
public class AppConfigService {

    private final AppReleaseConfigRepository repository;

    /**
     * Configuration for a platform and the build asking.
     *
     * <p>No row means nothing is enforced. That is the important case, not an edge case: an
     * unknown platform, a typo, a table that was never seeded — none of them should be able to
     * lock users out of the app. Enforcement is opt-in, and only ever from a row that says so.
     *
     * @param platform client platform, case-insensitive
     * @param build    the installed build number, or a negative value when the client did not say
     */
    @Transactional(readOnly = true)
    public AppConfigView forClient(String platform, int build) {
        if (platform == null || platform.isBlank()) return AppConfigView.unrestricted();
        AppReleaseConfig config = repository.findById(platform.trim().toUpperCase()).orElse(null);
        if (config == null) return AppConfigView.unrestricted();

        // A client that did not report its build is not gated. Refusing to run on a missing
        // number would brick anything that fails to read its own package info.
        final boolean required = build >= 0 && build < config.getMinBuild();
        final boolean recommended = build >= 0 && !required && build < config.getRecommendedBuild();

        return new AppConfigView(
                required, recommended,
                config.getMinBuild(), config.getRecommendedBuild(),
                config.getUpdateMessage(), config.getStoreUrl(),
                config.getRatingMinSuccesses(), config.getRatingMinDaysInstalled(),
                config.getRatingMinDaysBetweenAsks());
    }

    /**
     * What the app is told.
     *
     * @param updateRequired    the build is below the supported minimum and must not continue
     * @param updateRecommended a newer build exists and is worth nudging about
     */
    public record AppConfigView(boolean updateRequired, boolean updateRecommended,
                                int minBuild, int recommendedBuild,
                                String updateMessage, String storeUrl,
                                int ratingMinSuccesses, int ratingMinDaysInstalled,
                                int ratingMinDaysBetweenAsks) {

        /** Nothing configured: no gate, no nudge, and rating defaults the app can live with. */
        static AppConfigView unrestricted() {
            return new AppConfigView(false, false, 0, 0, null, null, 3, 3, 60);
        }
    }
}
