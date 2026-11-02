package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.Customers;
import com.TreDL.ecommerce.repository.CustomersRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class RegistrationService {
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+");
    private final CustomersRepository customers;
    private final PasswordEncoder passwords;
    public RegistrationService(CustomersRepository customers, PasswordEncoder passwords) {
        this.customers=customers; this.passwords=passwords;
    }
    public static String normalizeEmail(String email) { return email==null?"":email.trim().toLowerCase(Locale.ROOT); }
    public static final class InvalidRegistration extends RuntimeException {
        private final String code;
        public InvalidRegistration(String code) { super("Registrazione non valida"); this.code=code; }
        public String code() { return code; }
    }
    @Transactional
    public void register(String username, String email, String password, String confirmation) {
        String name=username==null?"":username.strip();
        String normalized=normalizeEmail(email);
        if(name.length()<3 || name.length()>50 || name.codePoints().anyMatch(Character::isISOControl)) throw new InvalidRegistration("usernameInvalid");
        if(normalized.length()>254 || normalized.indexOf('@')>64 || !EMAIL.matcher(normalized).matches()) throw new InvalidRegistration("emailInvalid");
        if(password==null || password.isBlank() || password.codePointCount(0,password.length())<12
                || password.getBytes(StandardCharsets.UTF_8).length>72
                || password.codePoints().noneMatch(Character::isLetter) || password.codePoints().noneMatch(Character::isDigit)
                || password.codePoints().anyMatch(Character::isISOControl)) throw new InvalidRegistration("passwordInvalid");
        if(!password.equals(confirmation)) throw new InvalidRegistration("password");
        if(customers.findByEmail(normalized).isPresent()) throw new InvalidRegistration("email");
        if(customers.findByUsername(name).isPresent()) throw new InvalidRegistration("username");
        Customers customer=new Customers();
        customer.setUsername(name); customer.setEmail(normalized);
        customer.setPassword(passwords.encode(password)); customer.setRole("USER");
        customers.saveAndFlush(customer);
    }
}
