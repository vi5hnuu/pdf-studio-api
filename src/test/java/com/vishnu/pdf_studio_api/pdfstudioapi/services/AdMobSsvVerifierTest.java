package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rewarded endpoint used to grant credits to anyone who asked. Ad credits now depend on a
 * signed AdMob callback, so the checks that gate it must hold.
 */
class AdMobSsvVerifierTest {

    private final AdMobSsvVerifier verifier = new AdMobSsvVerifier(
            "https://gstatic.com/admob/reward/verifier-keys.json", true);

    @Test
    void rejectsACallbackWithNoSignature() {
        assertFalse(verifier.verify("ad_network=5450213213286189855&reward_amount=1&user_id=u1"));
    }

    @Test
    void rejectsAnEmptyQuery() {
        assertFalse(verifier.verify(""));
        assertFalse(verifier.verify(null));
    }

    @Test
    void rejectsACallbackWhoseSignatureDoesNotMatchAnyKnownKey() {
        String query = "ad_network=5450213213286189855&reward_amount=1&reward_item=credits"
                + "&timestamp=" + System.currentTimeMillis()
                + "&transaction_id=abc123&user_id=u1&key_id=0&signature=bm90LWEtc2lnbmF0dXJl";

        assertFalse(verifier.verify(query));
    }

    @Test
    void rejectsAReplayOfAnOldCallback() {
        long twoHoursAgo = System.currentTimeMillis() - 2 * 60 * 60 * 1000;
        String query = "reward_amount=1&timestamp=" + twoHoursAgo
                + "&transaction_id=abc123&user_id=u1&key_id=3335741209&signature=AAAA";

        assertFalse(verifier.verify(query));
    }

    @Test
    void parsesTheQueryWithoutDecodingSoTheSignedBytesAreUnchanged() {
        // The signature covers the raw query, so a value containing %3A must stay escaped.
        Map<String, String> params = AdMobSsvVerifier.parse(
                "user_id=web%3Auser1&transaction_id=t1&signature=AAAA");

        assertEquals("web%3Auser1", params.get("user_id"));
        assertEquals("t1", params.get("transaction_id"));
    }

    @Test
    void verificationCanBeTurnedOffOnlyDeliberately() {
        assertTrue(verifier.required());
        assertFalse(new AdMobSsvVerifier("https://example.test/keys.json", false).required());
    }
}
