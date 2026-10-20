package com.vishnu.pdf_studio_api.pdfstudioapi.controllers;

import com.vishnu.pdf_studio_api.pdfstudioapi.security.CurrentUser;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.AdMobSsvVerifier;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.CreditsService;
import com.vishnu.pdf_studio_api.pdfstudioapi.util.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Credit endpoints consumed by the app: balance, the tool price list, INAPP purchase
 * redemption, and the two free-earning actions (rewarded ad, daily allowance). All are
 * authenticated; the userId is taken from the verified access token, never the body.
 */
@RestController
@RequestMapping("/api/v1/credits")
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class CreditsController {

    private final CreditsService creditsService;
    private final AdMobSsvVerifier ssvVerifier;

    @GetMapping("/balance")
    public ResponseEntity<Map<String, Object>> balance(HttpServletRequest request) {
        String userId = CurrentUser.requireId();
        String ip = ClientIp.of(request);
        int balance = creditsService.getBalance(userId, ip);
        // Ad-free rides along on the call the app already makes every launch, so the client
        // never has to trust its own cached copy while it is online.
        boolean adFree = creditsService.isAdFree(userId, ip);
        return ResponseEntity.ok(Map.of("success", true,
                "data", Map.of("credits", balance, "ad_free", adFree)));
    }

    /** Full tool price list for the UI (0 = free). */
    @GetMapping("/costs")
    public ResponseEntity<Map<String, Object>> costs() {
        return ResponseEntity.ok(Map.of("success", true, "data", creditsService.listCosts()));
    }

    /**
     * The caller's credit history, newest first.
     *
     * <p>Scoped to the authenticated user by construction — the userId comes from the token,
     * never from a parameter, so one user cannot read another's ledger.
     */
    @GetMapping("/ledger")
    public ResponseEntity<Map<String, Object>> ledger(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        var result = creditsService.listLedger(CurrentUser.requireId(), page, size);
        return ResponseEntity.ok(Map.of("success", true, "data", result));
    }

    @PostMapping("/purchase")
    public ResponseEntity<Map<String, Object>> purchase(@RequestBody Map<String, String> body,
                                                        HttpServletRequest request) {
        String token = body.get("purchaseToken");
        String productId = body.get("productId");
        var outcome = creditsService.purchaseCredits(
                CurrentUser.requireId(), token, productId, ClientIp.of(request));
        // "credits" stays the caller's balance, which is what every client reads, so an older
        // build keeps working. The flags let a newer one tell an idempotent replay from a fresh
        // grant — a distinction it previously had to guess at from a 409, and guessed wrong.
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Credits added.",
                "data", Map.of(
                        "credits", outcome.callerBalance(),
                        "creditsGranted", outcome.creditsGranted(),
                        "alreadyGranted", outcome.alreadyGranted(),
                        "creditedToCaller", outcome.creditedToCaller())));
    }

    /**
     * Reports the balance after an ad, without granting anything.
     *
     * <p>This used to grant on the caller's word: any authenticated request carrying an
     * idempotency key was credited, so nothing tied a credit to an ad actually playing. The
     * grant now happens only in {@link #admobCallback}, which Google calls directly and signs.
     * The endpoint stays so the app can refresh its balance once an ad finishes.
     */
    @PostMapping("/rewarded")
    public ResponseEntity<Map<String, Object>> rewarded() {
        int balance = creditsService.peekBalance(CurrentUser.requireId()).orElse(0);
        return ResponseEntity.ok(Map.of("success", true,
                "data", Map.of("credits", balance, "granted", 0)));
    }

    /**
     * AdMob's server-side verification callback — the only path that grants ad credits.
     *
     * <p>Google calls this when a rewarded ad genuinely completes, signing the query string with
     * one of its rotating keys. The signature is checked before anything is granted, and AdMob's
     * {@code transaction_id} is used as the idempotency key so a retried or replayed callback
     * credits once.
     *
     * <p>Always answers 200: AdMob retries on any other status, and a forged callback should not
     * earn a retry. Whether a grant happened is in the log, not the response.
     */
    @GetMapping("/admob-ssv")
    public ResponseEntity<String> admobCallback(HttpServletRequest request) {
        String query = request.getQueryString();

        if (ssvVerifier.required() && !ssvVerifier.verify(query)) {
            log.warn("Rejected an AdMob SSV callback that failed verification.");
            return ResponseEntity.ok("ignored");
        }

        var params = AdMobSsvVerifier.parse(query == null ? "" : query);
        String userId = params.get("user_id");
        String transactionId = params.get("transaction_id");
        if (userId == null || userId.isBlank() || transactionId == null || transactionId.isBlank()) {
            log.warn("AdMob SSV callback carried no user_id/transaction_id; nothing to credit.");
            return ResponseEntity.ok("ignored");
        }

        try {
            creditsService.grantRewardedAd(
                    URLDecoder.decode(userId, StandardCharsets.UTF_8),
                    transactionId,
                    ClientIp.of(request));
        } catch (Exception e) {
            // A cap being reached is an ordinary outcome, not a reason for AdMob to retry.
            log.info("AdMob SSV grant not applied for transaction {}: {}", transactionId, e.toString());
        }
        return ResponseEntity.ok("ok");
    }

    @PostMapping("/daily")
    public ResponseEntity<Map<String, Object>> daily(HttpServletRequest request) {
        var result = creditsService.claimDailyAllowance(CurrentUser.requireId(), ClientIp.of(request));
        return ResponseEntity.ok(Map.of("success", true, "message", "Daily credits claimed.",
                "data", Map.of("credits", result.balance(), "granted", result.granted())));
    }
}
