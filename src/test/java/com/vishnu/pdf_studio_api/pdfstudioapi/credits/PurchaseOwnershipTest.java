package com.vishnu.pdf_studio_api.pdfstudioapi.credits;

import com.vishnu.pdf_studio_api.pdfstudioapi.services.AuthInternalClient;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.CreditsService;
import com.vishnu.pdf_studio_api.pdfstudioapi.services.PlayStoreVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Who a purchase belongs to, and who may take it.
 *
 * <p>A purchase used to be credited to whoever was signed in when it was redeemed. A session is
 * not durable — a guest whose refresh token is rejected, or whose account the auth service purges
 * after its expiry, silently becomes a different principal — so a redelivered purchase arrived as
 * a stranger, got a 409, and the client read that as "already credited" and consumed it. The
 * purchase was then spent, unredeliverable, and the credits sat on an identity nobody held.
 *
 * <p>Play records the buying account in the purchase itself, which is the one durable link, so
 * these pin that it is what decides ownership.
 */
@SpringBootTest
class PurchaseOwnershipTest {

    private static final String PRODUCT = "pdfcraft_credits_10";

    @Autowired CreditsService creditsService;

    @MockBean PlayStoreVerifier playStoreVerifier;
    @MockBean AuthInternalClient authInternal;

    private String token() {
        return "tok-" + UUID.randomUUID();
    }

    /** Play reports this purchase as valid and owned by [accountId]. */
    private void playSays(String accountId) {
        when(playStoreVerifier.verifyInAppPurchase(anyString(), anyString()))
                .thenReturn(new PlayStoreVerifier.InAppPurchaseResult(true, "order-1", accountId));
    }

    @Test
    @DisplayName("credits go to the account Play recorded, not to whoever is calling")
    void creditsFollowThePlayAccount() {
        final String owner = "owner-" + UUID.randomUUID();
        playSays(owner);
        // A different session redeems: the identity changed between buying and redeeming.
        final String caller = "caller-" + UUID.randomUUID();
        int callerBefore = creditsService.getBalance(caller, null);
        int ownerBefore = creditsService.getBalance("app:" + owner, null);

        var outcome = creditsService.purchaseCredits(caller, token(), PRODUCT, null);

        assertFalse(outcome.creditedToCaller(), "the caller does not own this purchase");
        assertEquals(callerBefore, creditsService.getBalance(caller, null),
                "the caller's balance must not move");
        assertTrue(creditsService.getBalance("app:" + owner, null) > ownerBefore,
                "the owner recorded by Play must be credited");
    }

    @Test
    @DisplayName("a redelivered purchase is reported, never refused, and never credited twice")
    void redeliveryIsIdempotentAndNotAConflict() {
        final String user = "u-" + UUID.randomUUID();
        playSays(user);
        final String tok = token();

        var first = creditsService.purchaseCredits("app:" + user, tok, PRODUCT, null);
        assertTrue(first.creditedToCaller());
        assertFalse(first.alreadyGranted());
        int after = first.callerBalance();

        // Play redelivers the same purchase. This used to be a 409, which the client read as
        // success and acted on by consuming the purchase.
        var second = assertDoesNotThrow(
                () -> creditsService.purchaseCredits("app:" + user, tok, PRODUCT, null),
                "a redelivered purchase must not be refused");
        assertTrue(second.alreadyGranted());
        assertEquals(0, second.creditsGranted(), "a replay must not grant again");
        assertEquals(after, second.callerBalance(), "a replay must not change the balance");
    }

    @Test
    @DisplayName("an abandoned guest's purchase can be claimed by the session that replaced it")
    void abandonedGuestPurchaseIsRecoverable() {
        final String guest = "guest-" + UUID.randomUUID();
        playSays(guest);
        final String tok = token();
        creditsService.purchaseCredits("app:" + guest, tok, PRODUCT, null);
        int stranded = creditsService.getBalance("app:" + guest, null);
        assertTrue(stranded > 0);

        // The app lost that session and is now someone else.
        final String newSession = "new-" + UUID.randomUUID();
        int before = creditsService.getBalance(newSession, null);
        when(authInternal.isGuest("app:" + guest)).thenReturn(Optional.of(true));

        int moved = creditsService.claimPurchase(newSession, tok, PRODUCT, null);

        assertTrue(moved > 0, "the stranded credits should move");
        assertEquals(before + moved, creditsService.getBalance(newSession, null));
        assertEquals(0, creditsService.getBalance("app:" + guest, null),
                "the abandoned account must be drained, so a replay has nothing to move");
    }

    @Test
    @DisplayName("a registered account's purchase cannot be claimed by anyone else")
    void registeredAccountPurchaseIsNotClaimable() {
        // The multi-user case: two people share a device. Nothing the second can present should
        // move the first person's credits, and holding the Play token is not an exception.
        final String registered = "real-" + UUID.randomUUID();
        playSays(registered);
        final String tok = token();
        creditsService.purchaseCredits("app:" + registered, tok, PRODUCT, null);
        int ownerBalance = creditsService.getBalance("app:" + registered, null);

        when(authInternal.isGuest("app:" + registered)).thenReturn(Optional.of(false));

        var ex = assertThrows(ResponseStatusException.class,
                () -> creditsService.claimPurchase("other-" + UUID.randomUUID(), tok, PRODUCT, null));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertEquals(ownerBalance, creditsService.getBalance("app:" + registered, null),
                "the owner's balance must be untouched");
    }

    @Test
    @DisplayName("an owner whose type cannot be established is refused — unknown is not permission")
    void unknownOwnerTypeIsRefused() {
        final String owner = "unknown-" + UUID.randomUUID();
        playSays(owner);
        final String tok = token();
        creditsService.purchaseCredits("app:" + owner, tok, PRODUCT, null);

        // Auth unreachable. Allowing the transfer here would hand a purchase to a stranger on a
        // network blip, so it has to fail closed.
        when(authInternal.isGuest(any())).thenReturn(Optional.empty());

        var ex = assertThrows(ResponseStatusException.class,
                () -> creditsService.claimPurchase("other-" + UUID.randomUUID(), tok, PRODUCT, null));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    @DisplayName("a Play account id this service would never issue is not trusted as an identity")
    void aMalformedPlayAccountIdFallsBackToTheCaller() {
        // The obfuscated external account id is attached by the client, so it is the one subject
        // here that no token vouches for. Left unchecked it became an account key and a path
        // segment in a server-to-server URL.
        playSays("../../internal/users/victim");
        final String caller = "app:CALLER" + UUID.randomUUID().toString().replace("-", "");
        int before = creditsService.getBalance(caller, null);

        var outcome = creditsService.purchaseCredits(caller, token(), PRODUCT, null);

        assertTrue(outcome.creditedToCaller(),
                "with no usable account id the caller — who holds a verified token — is credited");
        assertTrue(creditsService.getBalance(caller, null) > before);
    }

    @Test
    @DisplayName("a guest transfer works whichever way the two ids sort")
    void transferWorksInBothLockOrders() {
        // The transfer is the only operation holding two account locks, and a claim runs
        // guest-to-guest, so the rows are locked in a fixed order by key rather than
        // drain-then-credit — two opposite claims would otherwise deadlock. Both branches of
        // that ordering have to move the credits; exercising only one would leave half of it
        // untested and the deadlock is not reproducible from a single-threaded test.
        final String low = "app:AAA" + UUID.randomUUID().toString().replace("-", "");
        final String high = "app:ZZZ" + UUID.randomUUID().toString().replace("-", "");

        int lowBalance = creditsService.getBalance(low, null);
        int highBalance = creditsService.getBalance(high, null);
        assertTrue(lowBalance > 0, "the welcome grant should have given the guest something to move");

        assertEquals(lowBalance, creditsService.transferGuestBalance(low, high, null),
                "source sorting before target");
        assertEquals(0, creditsService.getBalance(low, null));
        assertEquals(highBalance + lowBalance, creditsService.getBalance(high, null));

        final String other = "app:MMM" + UUID.randomUUID().toString().replace("-", "");
        int otherBalance = creditsService.getBalance(other, null);
        int highNow = creditsService.getBalance(high, null);
        assertEquals(highNow, creditsService.transferGuestBalance(high, other, null),
                "source sorting after target");
        assertEquals(otherBalance + highNow, creditsService.getBalance(other, null));
    }
}
