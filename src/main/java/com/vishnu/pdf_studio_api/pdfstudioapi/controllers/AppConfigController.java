package com.vishnu.pdf_studio_api.pdfstudioapi.controllers;

import com.vishnu.pdf_studio_api.pdfstudioapi.services.AppConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration the app reads at launch.
 *
 * <p>Deliberately unauthenticated. If a change to authentication is the reason a build has to be
 * forced out, requiring authentication to discover that is circular — and a build that cannot get
 * a token is exactly the one most likely to need telling.
 */
@RestController
@RequestMapping("/api/v1/app-config")
@RequiredArgsConstructor
public class AppConfigController {

    private final AppConfigService appConfigService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> get(
            @RequestParam(value = "platform", required = false) String platform,
            @RequestParam(value = "build", required = false, defaultValue = "-1") int build) {

        var view = appConfigService.forClient(platform, build);

        // HashMap, not Map.of: updateMessage and storeUrl are nullable and Map.of rejects nulls.
        Map<String, Object> data = new HashMap<>();
        data.put("updateRequired", view.updateRequired());
        data.put("updateRecommended", view.updateRecommended());
        data.put("minBuild", view.minBuild());
        data.put("recommendedBuild", view.recommendedBuild());
        data.put("updateMessage", view.updateMessage());
        data.put("storeUrl", view.storeUrl());
        data.put("ratingMinSuccesses", view.ratingMinSuccesses());
        data.put("ratingMinDaysInstalled", view.ratingMinDaysInstalled());
        data.put("ratingMinDaysBetweenAsks", view.ratingMinDaysBetweenAsks());

        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }
}
