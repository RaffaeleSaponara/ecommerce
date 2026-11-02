package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.security.RecoveryRateLimiter;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

/** No transaction here: SMTP and local session expiry run after the store's commit. */
@Service
public class PasswordRecoveryService {
    private final PasswordResetStore store;
    private final RecoveryMail mail;
    private final RecoveryRateLimiter limits;
    private final SessionRegistry sessions;
    public PasswordRecoveryService(PasswordResetStore store,RecoveryMail mail,RecoveryRateLimiter limits,SessionRegistry sessions) {
        this.store=store;this.mail=mail;this.limits=limits;this.sessions=sessions;
    }
    public void request(String email,String ip) {
        String normalized=RegistrationService.normalizeEmail(email);
        if(!limits.ip(ip) || normalized.length()>254 || !limits.email(normalized) || !mail.enabled())return;
        PasswordResetStore.Issued issued=store.issue(normalized);
        if(issued!=null)mail.recovery(issued.email,issued.token);
    }
    public void reset(String token,String password,String confirmation) {
        String email=store.consume(token,password,confirmation);
        String normalized=RegistrationService.normalizeEmail(email);
        for(Object principal:sessions.getAllPrincipals()) {
            if(principal instanceof UserDetails user && RegistrationService.normalizeEmail(user.getUsername()).equals(normalized))
                sessions.getAllSessions(principal,false).forEach(session->session.expireNow());
        }
        mail.changed(RegistrationService.normalizeEmail(email));
    }
}
