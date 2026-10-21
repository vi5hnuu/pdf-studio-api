package com.vishnu.pdf_studio_api.pdfstudioapi.services;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How long each {@code isGuest} answer may be reused.
 *
 * <p>The lifetimes are the whole behaviour, and they are deliberately unequal. A single TTL meant a
 * guest who converted and bought straight away was refused for up to a minute by a cached "still a
 * guest" — with a message telling them to sign in, which they just had. Guest status only ever
 * changes in that one direction, so that answer is the only one that must never be served stale.
 *
 * <p>Driven by a fake clock against the real cache, so this pins the policy the service actually
 * runs rather than a copy of it.
 */
class IsGuestCacheTest {

    /** A clock the test advances by hand, so a 60-second TTL costs no wall time. */
    private static final class FakeTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration by) {
            nanos.addAndGet(by.toNanos());
        }
    }

    private final FakeTicker ticker = new FakeTicker();
    private final Cache<String, Optional<Boolean>> cache =
            AuthInternalClient.newIsGuestCache(ticker);

    @Test
    @DisplayName("a guest answer is never reused, so converting then buying is not refused")
    void guestAnswersAreNotCached() {
        cache.put("u1", Optional.of(true));

        assertNull(cache.getIfPresent("u1"),
                "a guest answer must be re-fetched immediately: the account may have just converted");
    }

    @Test
    @DisplayName("a signed-in answer is reused, which is what the cache is for")
    void signedInAnswersAreCached() {
        cache.put("u2", Optional.of(false));

        assertEquals(Optional.of(false), cache.getIfPresent("u2"));

        // A real account never becomes a guest, so this answer cannot go stale harmfully.
        ticker.advance(Duration.ofSeconds(59));
        assertEquals(Optional.of(false), cache.getIfPresent("u2"),
                "still inside the minute");

        ticker.advance(Duration.ofSeconds(2));
        assertNull(cache.getIfPresent("u2"), "and expires after it");
    }

    @Test
    @DisplayName("an unknown answer is held briefly, to spare an auth service that is already struggling")
    void unknownAnswersAreCachedBriefly() {
        cache.put("u3", Optional.empty());

        assertEquals(Optional.empty(), cache.getIfPresent("u3"));

        // Short, because callers fail closed on unknown — every second of it refuses a purchase.
        ticker.advance(Duration.ofSeconds(6));
        assertNull(cache.getIfPresent("u3"));
    }

    @Test
    @DisplayName("reading an answer does not extend its life")
    void readsDoNotExtendTheTtl() {
        cache.put("u4", Optional.of(false));

        ticker.advance(Duration.ofSeconds(40));
        assertEquals(Optional.of(false), cache.getIfPresent("u4"));

        // Were a read to renew the TTL, a busy account's answer could be held indefinitely.
        ticker.advance(Duration.ofSeconds(25));
        assertNull(cache.getIfPresent("u4"));
    }
}
