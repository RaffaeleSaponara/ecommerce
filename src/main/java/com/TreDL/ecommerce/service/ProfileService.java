package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.Customers;
import com.TreDL.ecommerce.repository.CustomersRepository;
import jakarta.persistence.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;

@Service
public class ProfileService {
    private final CustomersRepository customers;
    private final PasswordEncoder passwords;
    @PersistenceContext private EntityManager em;
    public ProfileService(CustomersRepository customers,PasswordEncoder passwords) {this.customers=customers;this.passwords=passwords;}
    public record Profile(String username,String email) {}
    @Transactional(readOnly=true)
    public Profile view(String authenticatedEmail) {
        Customers customer=customer(authenticatedEmail);
        return new Profile(customer.getUsername(),customer.getEmail());
    }
    @Transactional
    public void updateUsername(String authenticatedEmail,String username) {
        String name=RegistrationService.validateUsername(username);
        Customers customer=locked(authenticatedEmail);
        customers.findByUsername(name).ifPresent(existing->{
            if(!existing.getId().equals(customer.getId())) throw new RegistrationService.InvalidRegistration("username");
        });
        customer.setUsername(name);
        customers.flush();
    }
    @Transactional
    public void changePassword(String authenticatedEmail,String current,String next,String confirmation) {
        Customers customer=locked(authenticatedEmail);
        if(current==null || current.isBlank() || current.getBytes(StandardCharsets.UTF_8).length>72
                || !passwords.matches(current,customer.getPassword())) throw new RegistrationService.InvalidRegistration("currentPassword");
        RegistrationService.validatePassword(next,confirmation);
        if(passwords.matches(next,customer.getPassword())) throw new RegistrationService.InvalidRegistration("passwordReused");
        customer.setPassword(passwords.encode(next));
        em.createQuery("delete from PasswordResetToken t where t.customerId=:id").setParameter("id",customer.getId()).executeUpdate();
        customers.flush();
    }
    private Customers customer(String email) {
        if(email==null || email.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return customers.findByEmail(email).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
    private Customers locked(String email) {
        Customers customer=customer(email);
        Customers locked=customers.findByIdForUpdate(customer.getId()).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        em.refresh(locked,LockModeType.PESSIMISTIC_WRITE);
        return locked;
    }
}
