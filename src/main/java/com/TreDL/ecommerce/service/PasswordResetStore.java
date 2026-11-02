package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.*;
import com.TreDL.ecommerce.repository.CustomersRepository;
import com.TreDL.ecommerce.security.RecoveryRateLimiter;
import jakarta.persistence.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class PasswordResetStore {
    private final CustomersRepository customers;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final RecoveryRateLimiter limits;
    private final SecureRandom random=new SecureRandom();
    @PersistenceContext private EntityManager em;
    public PasswordResetStore(CustomersRepository customers,PasswordEncoder passwords,Clock clock,RecoveryRateLimiter limits) {
        this.customers=customers;this.passwords=passwords;this.clock=clock;this.limits=limits;
    }
    // Deliberately not a record: its toString must never expose a reset token.
    static final class Issued {
        final String email,token;
        Issued(String email,String token){this.email=email;this.token=token;}
    }
    public static final class InvalidToken extends RuntimeException {
        public InvalidToken(){super("Link non valido o scaduto. Richiedi un nuovo recupero password.");}
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Issued issue(String normalizedEmail) {
        Customers found=customers.findByEmail(normalizedEmail).orElse(null);
        if(found==null)return null;
        String canonical=RegistrationService.normalizeEmail(found.getEmail());
        if(!canonical.equals(normalizedEmail) && !limits.email(canonical))return null;
        Customers customer=em.find(Customers.class,found.getId(),LockModeType.PESSIMISTIC_WRITE);
        em.refresh(customer,LockModeType.PESSIMISTIC_WRITE);
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        String raw=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        PasswordResetToken stored=em.find(PasswordResetToken.class,customer.getId());
        if(stored==null)em.persist(new PasswordResetToken(customer.getId(),hash(raw),now().plusMinutes(30)));
        else stored.replace(hash(raw),now().plusMinutes(30));
        em.flush();
        return new Issued(canonical,raw);
    }
    @Transactional(readOnly=true)
    public boolean valid(String raw) {
        if(!format(raw))return false;
        return em.createQuery("select count(t) from PasswordResetToken t where t.tokenHash=:hash and t.expiresAt>:now",Long.class)
                .setParameter("hash",hash(raw)).setParameter("now",now()).getSingleResult()>0;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public String consume(String raw,String password,String confirmation) {
        if(!format(raw))throw new InvalidToken();
        byte[] digest=hash(raw);
        List<Long> owners=em.createQuery("select t.customerId from PasswordResetToken t where t.tokenHash=:hash",Long.class).setParameter("hash",digest).getResultList();
        if(owners.isEmpty())throw new InvalidToken();
        Customers customer=em.find(Customers.class,owners.get(0),LockModeType.PESSIMISTIC_WRITE);
        if(customer==null)throw new InvalidToken();
        em.refresh(customer,LockModeType.PESSIMISTIC_WRITE);
        PasswordResetToken token=em.find(PasswordResetToken.class,customer.getId(),LockModeType.PESSIMISTIC_WRITE);
        if(token==null || !MessageDigest.isEqual(digest,token.getTokenHash()) || !token.getExpiresAt().isAfter(now()))throw new InvalidToken();
        RegistrationService.validatePassword(password,confirmation);
        customer.setPassword(passwords.encode(password));em.remove(token);em.flush();
        return customer.getEmail();
    }
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);}
    private static boolean format(String raw){return raw!=null && raw.matches("[A-Za-z0-9_-]{43}");}
    public static byte[] hash(String raw) {
        try{return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.US_ASCII));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
