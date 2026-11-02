package com.TreDL.ecommerce.security;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class AuthAttemptLimiterTests {
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        void advance(Duration duration) {now.updateAndGet(t->t.plus(duration));}
        public ZoneId getZone() {return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone) {return this;}
        public Instant instant() {return now.get();}
    }
    MutableClock clock=new MutableClock();
    AuthAttemptLimiter limits=new AuthAttemptLimiter(clock);
    void fail(String email,String ip) {limits.complete(limits.beginLogin(email,ip),false);}
    @Test void fiveFailuresBlockCaseVariantsAndExpireWithoutExtendingOnRejectedRequests() {
        for(int i=0;i<4;i++) fail("Alice@example.com","ip");
        assertThat(limits.complete(limits.beginLogin(" ALICE@EXAMPLE.COM ","ip"),false)).isFalse();
        assertThatThrownBy(()->limits.beginLogin("alice@example.com","other-ip")).isInstanceOf(AuthAttemptLimiter.Limited.class);
        clock.advance(Duration.ofMinutes(14));
        assertThatThrownBy(()->limits.beginLogin("alice@example.com","ip")).isInstanceOf(AuthAttemptLimiter.Limited.class);
        clock.advance(Duration.ofMinutes(1));
        assertThat(limits.complete(limits.beginLogin("alice@example.com","ip"),true)).isTrue();
    }
    @Test void successfulAuthenticationClearsAccountAndWindowDropsOldFailures() {
        for(int i=0;i<4;i++) fail("a","ip");
        assertThat(limits.complete(limits.beginLogin("a","ip"),true)).isTrue();
        for(int i=0;i<4;i++) fail("a","ip");
        clock.advance(Duration.ofMinutes(15));
        for(int i=0;i<4;i++) fail("a","ip");
        assertThat(limits.complete(limits.beginLogin("a","ip"),true)).isTrue();
    }
    @Test void ipLimitSurvivesSuccessfulLoginsOfOtherAccountsAndExpires() {
        for(int i=0;i<30;i++) {
            fail("missing-"+i,"same-ip");
            if(i<29) assertThat(limits.complete(limits.beginLogin("valid-"+i,"same-ip"),true)).isTrue();
        }
        assertThatThrownBy(()->limits.beginLogin("another","same-ip")).isInstanceOf(AuthAttemptLimiter.Limited.class);
        assertThat(limits.complete(limits.beginLogin("another","different-ip"),true)).isTrue();
        clock.advance(Duration.ofMinutes(15));
        assertThat(limits.complete(limits.beginLogin("another","same-ip"),true)).isTrue();
    }
    @Test void concurrentRequestsCannotReserveMoreThanFiveAccountAttempts() throws Exception {
        var pool=Executors.newFixedThreadPool(20);var ready=new CountDownLatch(20);var start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> requests=new ArrayList<>();
            for(int i=0;i<20;i++) requests.add(pool.submit(()->{
                ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Race not released");
                try {fail("same-account","ip");return true;}catch(AuthAttemptLimiter.Limited limited){return false;}
            }));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            int admitted=0;for(var request:requests)if(request.get(10,TimeUnit.SECONDS))admitted++;
            assertThat(admitted).isEqualTo(5);
        } finally {start.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void cancellationReleasesReservationsExactlyOnce() {
        List<AuthAttemptLimiter.Ticket> tickets=new ArrayList<>();
        for(int i=0;i<5;i++)tickets.add(limits.beginLogin("a","ip"));
        assertThatThrownBy(()->limits.beginLogin("a","ip")).isInstanceOf(AuthAttemptLimiter.Limited.class);
        limits.cancel(tickets.get(0));limits.cancel(tickets.get(0));
        var replacement=limits.beginLogin("a","ip");
        assertThatThrownBy(()->limits.beginLogin("a","ip")).isInstanceOf(AuthAttemptLimiter.Limited.class);
        tickets.forEach(limits::cancel);limits.cancel(replacement);
        assertThat(limits.complete(limits.beginLogin("a","ip"),true)).isTrue();
    }
    @Test void signupIsSeparateBoundedAndUsesRollingExpiry() {
        for(int i=0;i<10;i++)limits.signup("ip");
        assertThatThrownBy(()->limits.signup("ip")).isInstanceOf(AuthAttemptLimiter.Limited.class);
        assertThat(limits.complete(limits.beginLogin("a","ip"),true)).isTrue();
        clock.advance(Duration.ofMinutes(15));limits.signup("ip");
    }
    @Test void capacityNeverEvictsActiveLimitsAndExpiredKeysAreReclaimed() {
        limits=new AuthAttemptLimiter(clock,2,2);
        fail("a","ip1");fail("b","ip2");
        assertThatThrownBy(()->limits.beginLogin("c","ip1")).isInstanceOf(AuthAttemptLimiter.Limited.class);
        var known=limits.beginLogin("a","ip1");limits.cancel(known);
        limits.signup("ip1");limits.signup("ip2");
        assertThatThrownBy(()->limits.signup("ip3")).isInstanceOf(AuthAttemptLimiter.Limited.class);
        clock.advance(Duration.ofMinutes(15));
        assertThat(limits.complete(limits.beginLogin("c","ip3"),true)).isTrue();limits.signup("ip3");
    }
}
