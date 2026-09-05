package com.vishnu.pdf_studio_api.pdfstudioapi.controllers;

import com.vishnu.pdf_studio_api.pdfstudioapi.security.CurrentUser;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.CreditsService;
import com.vishnu.pdf_studio_api.pdfstudioapi.util.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Credit endpoints consumed by the app: balance, the tool price list, INAPP purchase
 * redemption, and the two free-earning actions (rewarded ad, daily allowance). All are
 * authenticated; the userId is taken from the verified access token, never the body.
 */
@RestController
@RequestMapping("/api/v1/credits")
@RequiredArgsConstructor
public class CreditsController {

    private final CreditsService creditsService;

    @GetMapping("/balance")
    public ResponseEntity<Map<String, Object>> balance(HttpServletRequest request) {
        int balance = creditsService.getBalance(CurrentUser.requireId(), ClientIp.of(request));
        return ResponseEntity.ok(Map.of("success", true, "data", Map.of("credits", balance)));
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
        int balance = creditsService.purchaseCredits(
                CurrentUser.requireId(), token, productId, ClientIp.of(request));
        return ResponseEntity.ok(Map.of("success", true, "message", "Credits added.",
                "data", Map.of("credits", balance)));
    }

    @PostMapping("/rewarded")
    public ResponseEntity<Map<String, Object>> rewarded(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        var result = creditsService.grantRewardedAd(CurrentUser.requireId(), idempotencyKey, ClientIp.of(request));
        return ResponseEntity.ok(Map.of("success", true,
                "data", Map.of("credits", result.balance(), "granted", result.granted())));
    }

    @PostMapping("/daily")
    public ResponseEntity<Map<String, Object>> daily(HttpServletRequest request) {
        var result = creditsService.claimDailyAllowance(CurrentUser.requireId(), ClientIp.of(request));
        return ResponseEntity.ok(Map.of("success", true, "message", "Daily credits claimed.",
                "data", Map.of("credits", result.balance(), "granted", result.granted())));
    }
}
