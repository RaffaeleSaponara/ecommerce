package com.TreDL.ecommerce.security;
import org.springframework.stereotype.Component;
import java.time.Clock;

/** Same bounded policy, independent state from recovery and authentication. */
@Component
public class VerificationRateLimiter {
    private final RecoveryRateLimiter windows;
    public VerificationRateLimiter(Clock clock){windows=new RecoveryRateLimiter(clock);}
    public boolean email(String email){return windows.email(email);}
    public boolean ip(String ip){return windows.ip(ip);}
    public boolean confirmationIp(String ip){return windows.resetIp(ip);}
}
