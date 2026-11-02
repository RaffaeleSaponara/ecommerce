package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.repository.CustomersRepository;
import com.TreDL.ecommerce.model.Customers;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegistrationTests {
    CustomersRepository customers=mock(CustomersRepository.class);
    BCryptPasswordEncoder encoder=new BCryptPasswordEncoder(4);
    RegistrationService service=new RegistrationService(customers,encoder);
    @Test void validCanonicalEmailHasOnlyEncodedPasswordAndServerRole() {
        service.register("  Alice  ","  ALICE@Example.com  ","good-password1","good-password1");
        verify(customers).saveAndFlush(argThat(c -> c.getUsername().equals("Alice") && c.getEmail().equals("alice@example.com")
                && c.getRole().equals("USER") && !c.isEmailVerified() && !c.getPassword().equals("good-password1") && encoder.matches("good-password1",c.getPassword())));
    }
    @ParameterizedTest @ValueSource(strings={"", "   ", "no-email", "a@", "@example.com", "a..b@example.com", "a@-bad.com", "a@bad", "a b@example.com"})
    void badEmailIsRejected(String email) { rejected("Alice",email,"good-password1","good-password1","emailInvalid"); }
    @ParameterizedTest @ValueSource(strings={"", "   ", "short1", "onlyletterslong", "123456789012", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"})
    void badPasswordIsRejected(String password) { rejected("Alice","alice@example.com",password,password,"passwordInvalid"); }
    @Test void byteLimitAndMissingValuesAndMismatch() {
        rejected("Alice","alice@example.com","é".repeat(36)+"1","same","passwordInvalid");
        rejected("Alice","alice@example.com",null,null,"passwordInvalid");
        rejected("Alice",null,"good-password1","good-password1","emailInvalid");
        rejected("Alice","alice@example.com","good-password1","different1234","password");
        rejected("Alice","a".repeat(65)+"@example.com","good-password1","good-password1","emailInvalid");
        rejected("Alice","a@"+"b".repeat(250)+".com","good-password1","good-password1","emailInvalid");
    }
    @ParameterizedTest @ValueSource(strings={"", " ", "ab", "bad\nname", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void badUsernameIsRejected(String name) { rejected(name,"alice@example.com","good-password1","good-password1","usernameInvalid"); }
    @Test void duplicatesHaveDistinctMessages() {
        when(customers.findByEmail("alice@example.com")).thenReturn(java.util.Optional.of(new Customers()));
        rejected("Alice","ALICE@example.com","good-password1","good-password1","email");
        when(customers.findByUsername("Other")).thenReturn(java.util.Optional.of(new Customers()));
        rejected("Other","other@example.com","good-password1","good-password1","username");
    }
    void rejected(String name,String email,String password,String confirmation,String code) {
        assertThatThrownBy(()->service.register(name,email,password,confirmation)).isInstanceOfSatisfying(RegistrationService.InvalidRegistration.class,e->assertThat(e.code()).isEqualTo(code));
        verify(customers,never()).saveAndFlush(any());
    }
}
