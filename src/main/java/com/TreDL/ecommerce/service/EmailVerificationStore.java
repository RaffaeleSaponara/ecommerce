package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.*;
import com.TreDL.ecommerce.repository.CustomersRepository;
import com.TreDL.ecommerce.security.VerificationRateLimiter;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.security.*;
import java.util.*;

@Service
public class EmailVerificationStore {
    private final CustomersRepository customers;
    private final VerificationRateLimiter limits;
    private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    @PersistenceContext private EntityManager em;
    public EmailVerificationStore(CustomersRepository customers,VerificationRateLimiter limits,Clock clock){this.customers=customers;this.limits=limits;this.clock=clock;}
    static final class Issued {
        final String email,token;
        Issued(String email,String token){this.email=email;this.token=token;}
    }
    public static class LinkException extends RuntimeException {
        private final boolean expired;
        LinkException(boolean expired){super("Link di verifica non utilizzabile");this.expired=expired;}
        public boolean expired(){return expired;}
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Issued issue(String normalized) {
        Customers found=customers.findByEmail(normalized).orElse(null);
        if(found==null)return null;
        String canonical=RegistrationService.normalizeEmail(found.getEmail());
        if(!canonical.equals(normalized) && !limits.email(canonical))return null;
        Customers customer=em.find(Customers.class,found.getId(),LockModeType.PESSIMISTIC_WRITE);
        em.refresh(customer,LockModeType.PESSIMISTIC_WRITE);
        if(customer.isEmailVerified())return null;
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        String raw=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        EmailVerificationToken token=em.find(EmailVerificationToken.class,customer.getId());
        if(token==null)em.persist(new EmailVerificationToken(customer.getId(),PasswordResetStore.hash(raw),now().plusHours(24)));
        else token.replace(PasswordResetStore.hash(raw),now().plusHours(24));
        em.flush();return new Issued(canonical,raw);
    }
    @Transactional
    public void confirm(String raw) {
        if(raw==null || !raw.matches("[A-Za-z0-9_-]{43}"))throw new LinkException(false);
        byte[] hash=PasswordResetStore.hash(raw);
        var owners=em.createQuery("select t.customerId from EmailVerificationToken t where t.hash=:hash",Long.class).setParameter("hash",hash).getResultList();
        if(owners.isEmpty())throw new LinkException(false);
        Customers customer=em.find(Customers.class,owners.get(0),LockModeType.PESSIMISTIC_WRITE);
        if(customer==null)throw new LinkException(false);
        em.refresh(customer,LockModeType.PESSIMISTIC_WRITE);
        EmailVerificationToken token=em.find(EmailVerificationToken.class,customer.getId(),LockModeType.PESSIMISTIC_WRITE);
        if(token==null || !MessageDigest.isEqual(hash,token.getHash()) || customer.isEmailVerified())throw new LinkException(false);
        if(!token.getExpiresAt().isAfter(now()))throw new LinkException(true);
        customer.setEmailVerified(true);em.remove(token);em.flush();
    }
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);}
}
