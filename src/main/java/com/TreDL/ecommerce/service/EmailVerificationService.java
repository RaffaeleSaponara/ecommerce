package com.TreDL.ecommerce.service;
import com.TreDL.ecommerce.security.VerificationRateLimiter;
import org.springframework.stereotype.Service;

@Service
public class EmailVerificationService {
    private final EmailVerificationStore store;
    private final RecoveryMail mail;
    private final VerificationRateLimiter limits;
    public EmailVerificationService(EmailVerificationStore store,RecoveryMail mail,VerificationRateLimiter limits){this.store=store;this.mail=mail;this.limits=limits;}
    public void request(String email,String ip) {
        String normalized=RegistrationService.normalizeEmail(email);
        if(!limits.ip(ip) || normalized.length()>254 || !limits.email(normalized) || !mail.enabled())return;
        var issued=store.issue(normalized);
        if(issued!=null)mail.verification(issued.email,issued.token); // Store committed before SMTP.
    }
}
