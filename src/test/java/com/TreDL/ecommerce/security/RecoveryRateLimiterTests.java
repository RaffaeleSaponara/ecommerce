package com.TreDL.ecommerce.security;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class RecoveryRateLimiterTests {
    AuthAttemptLimiterTests.MutableClock clock=new AuthAttemptLimiterTests.MutableClock();
    RecoveryRateLimiter limits=new RecoveryRateLimiter(clock);
    @Test void windowsNormalizeEmailsAndAreIndependent() {
        for(int i=0;i<3;i++)assertThat(limits.email(" User@Example.com ")).isTrue();
        assertThat(limits.email("user@example.COM")).isFalse();
        for(int i=0;i<10;i++)assertThat(limits.ip("ip")).isTrue();
        assertThat(limits.ip("ip")).isFalse();assertThat(limits.ip("other")).isTrue();
        for(int i=0;i<30;i++)assertThat(limits.resetIp("ip")).isTrue();
        assertThat(limits.resetIp("ip")).isFalse();
        clock.advance(Duration.ofMinutes(15));
        assertThat(limits.email("user@example.com")).isTrue();assertThat(limits.ip("ip")).isTrue();assertThat(limits.resetIp("ip")).isTrue();
    }
    @Test void memoryIsBoundedAndExpiredKeysAreReclaimed() {
        for(int i=0;i<10_000;i++)assertThat(limits.email("user"+i)).isTrue();
        assertThat(limits.email("another")).isFalse();
        clock.advance(Duration.ofMinutes(15));assertThat(limits.email("another")).isTrue();
    }
}
