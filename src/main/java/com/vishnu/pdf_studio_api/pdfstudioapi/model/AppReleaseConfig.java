package com.vishnu.pdf_studio_api.pdfstudioapi.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * What the app is told at launch: which builds are still supported, and how often to ask for a
 * rating.
 *
 * <p>A table rather than properties, for the same reason {@link ToolCreditCost} is one — these are
 * operational knobs, and a forced update is exactly the moment when redeploying to change a number
 * is the last thing anyone wants to be doing.
 */
@Entity
@Table(name = "app_release_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppReleaseConfig {

    /** ANDROID | IOS. */
    @Id
    @Column(name = "platform", length = 16, nullable = false)
    private String platform;

    /**
     * Builds below this are refused: the app shows an unskippable update screen.
     *
     * <p>A build number, not a version string: it is an integer and monotonic, where comparing
     * "4.0.10" against "4.0.9" as text gets the wrong answer.
     */
    @Column(name = "min_build", nullable = false)
    private int minBuild;

    /** Builds below this are nudged with a dismissible prompt. */
    @Column(name = "recommended_build", nullable = false)
    private int recommendedBuild;

    /** Optional copy for the gate; the app falls back to its own wording when null. */
    @Column(name = "update_message", length = 512)
    private String updateMessage;

    @Column(name = "store_url", length = 512)
    private String storeUrl;

    @Column(name = "rating_min_successes", nullable = false)
    private int ratingMinSuccesses;

    @Column(name = "rating_min_days_installed", nullable = false)
    private int ratingMinDaysInstalled;

    @Column(name = "rating_min_days_between_asks", nullable = false)
    private int ratingMinDaysBetweenAsks;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
