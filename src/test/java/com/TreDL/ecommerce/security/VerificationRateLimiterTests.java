package com.TreDL.ecommerce.security;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;

class VerificationRateLimiterTests {
    @Test void ipQuotaIsAtomicAndIndependentFromRecoveryAndLogin() {
        var clock=new AuthAttemptLimiterTests.MutableClock();var limits=new VerificationRateLimiter(clock);
        long admitted=IntStream.range(0,40).parallel().filter(i->limits.ip("same-ip")).count();assertThat(admitted).isEqualTo(10);
        assertThat(new RecoveryRateLimiter(clock).ip("same-ip")).isTrue();
        var login=new AuthAttemptLimiter(clock);assertThat(login.complete(login.beginLogin("a@example.com","same-ip"),true)).isTrue();
        for(int i=0;i<3;i++)assertThat(limits.email(" Alice@Example.com ")).isTrue();assertThat(limits.email("alice@example.com")).isFalse();
        clock.advance(Duration.ofMinutes(15));assertThat(limits.ip("same-ip")).isTrue();assertThat(limits.email("alice@example.com")).isTrue();
    }
}
