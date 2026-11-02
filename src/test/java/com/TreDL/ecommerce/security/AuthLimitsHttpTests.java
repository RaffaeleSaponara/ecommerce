package com.TreDL.ecommerce.security;

import com.TreDL.ecommerce.SecurityConfig;
import com.TreDL.ecommerce.controller.*;
import com.TreDL.ecommerce.model.Customers;
import com.TreDL.ecommerce.repository.CustomersRepository;
import com.TreDL.ecommerce.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.Duration;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({AuthController.class,HomeController.class,CustomersController.class})
@Import({SecurityConfig.class,CustomUserDetailsService.class,AuthLimitsHttpTests.TestLimits.class})
class AuthLimitsHttpTests {
    @TestConfiguration static class TestLimits {
        @Bean AuthAttemptLimiterTests.MutableClock testClock(){return new AuthAttemptLimiterTests.MutableClock();}
        @Bean @Primary AuthAttemptLimiter controlledLimits(AuthAttemptLimiterTests.MutableClock clock){return new AuthAttemptLimiter(clock);}
    }
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;
    @Autowired AuthAttemptLimiterTests.MutableClock clock;
    @MockitoBean CustomersRepository customers;
    @MockitoBean RegistrationService registration;
    @MockitoBean EmailVerificationService verification;
    @BeforeEach void fixture() {
        clock.advance(Duration.ofHours(1));
        Customers c=new Customers();c.setEmail("alice@example.com");c.setPassword(encoder.encode("test-password"));c.setRole("USER");
        c.setEmailVerified(true);
        when(customers.findByEmail("alice@example.com")).thenReturn(Optional.of(c));
    }
    MockHttpServletRequestBuilder login(String email,String password,String ip) {
        return post("/login").with(csrf()).param("username",email).param("password",password).with(r->{r.setRemoteAddr(ip);return r;});
    }
    @Test void accountLimitIncludesUnknownAccountsAndBlocksEvenCorrectPasswordUntilExpiry() throws Exception {
        for(String email:new String[]{"alice@example.com","unknown@example.com"}) {
            for(int i=0;i<4;i++)mvc.perform(login(email,"wrong","10.0.0.1")).andExpect(redirectedUrl("/login?error"));
            mvc.perform(login(email,"wrong","10.0.0.1")).andExpect(redirectedUrl("/login?limited"));
            mvc.perform(login(" "+email.toUpperCase()+" ","test-password","10.0.0.2")).andExpect(redirectedUrl("/login?limited")).andExpect(unauthenticated());
        }
        clock.advance(Duration.ofMinutes(15));
        mvc.perform(login("alice@example.com","test-password","10.0.0.1")).andExpect(redirectedUrl("/")).andExpect(authenticated());
        mvc.perform(get("/login").param("limited","" )).andExpect(content().string(org.hamcrest.Matchers.containsString("Accesso temporaneamente limitato")));
    }
    @Test void successfulLoginResetsAccountCounter() throws Exception {
        for(int i=0;i<4;i++)mvc.perform(login("alice@example.com","wrong","10.0.0.1"));
        mvc.perform(login("alice@example.com","test-password","10.0.0.1")).andExpect(authenticated());
        for(int i=0;i<4;i++)mvc.perform(login("alice@example.com","wrong","10.0.0.1")).andExpect(redirectedUrl("/login?error"));
    }
    @Test void databaseCollationAliasesShareTheCanonicalAccountLimit() throws Exception {
        Customers canonical=customers.findByEmail("alice@example.com").orElseThrow();
        // MySQL's common accent-insensitive collations can resolve this spelling to Alice.
        when(customers.findByEmail("álice@example.com")).thenReturn(Optional.of(canonical));
        for(int i=0;i<4;i++)mvc.perform(login("álice@example.com","wrong","10.0.0.5")).andExpect(redirectedUrl("/login?error"));
        mvc.perform(login("alice@example.com","wrong","10.0.0.5")).andExpect(redirectedUrl("/login?limited"));
        mvc.perform(login("álice@example.com","test-password","10.0.0.6")).andExpect(redirectedUrl("/login?limited")).andExpect(unauthenticated());
        clock.advance(Duration.ofMinutes(15));
        mvc.perform(login("álice@example.com","test-password","10.0.0.6")).andExpect(authenticated());
    }
    @Test void ipLimitIgnoresSpoofedForwardedHeaders() throws Exception {
        for(int i=0;i<30;i++)mvc.perform(login("missing"+i+"@example.com","wrong","10.0.0.3").header("X-Forwarded-For","192.0.2."+i));
        mvc.perform(login("alice@example.com","test-password","10.0.0.3").header("X-Forwarded-For","203.0.113.1"))
                .andExpect(redirectedUrl("/login?limited")).andExpect(unauthenticated());
        mvc.perform(login("alice@example.com","test-password","10.0.0.4")).andExpect(authenticated());
    }
    @Test void signupHasIndependentLimitAndCsrfRejectionsDoNotConsumeIt() throws Exception {
        for(int i=0;i<11;i++)mvc.perform(post("/signup")).andExpect(status().isForbidden());
        for(int i=0;i<10;i++)mvc.perform(post("/signup").with(csrf())).andExpect(redirectedUrl("/login?registered"));
        mvc.perform(post("/signup").with(csrf())).andExpect(redirectedUrl("/registrazione?error=limited"));
        verify(registration,times(10)).register(null,null,null,null);
        mvc.perform(login("alice@example.com","test-password","127.0.0.1")).andExpect(authenticated());
        clock.advance(Duration.ofMinutes(15));
        mvc.perform(post("/signup").with(csrf())).andExpect(redirectedUrl("/login?registered"));
    }
    @Test void csrfAndAlternativeEndpointsCannotAuthenticate() throws Exception {
        mvc.perform(post("/login").param("username","alice@example.com").param("password","test-password")).andExpect(status().isForbidden());
        for(String path:new String[]{"/auth/login","/auth/token"}) {
            var response=mvc.perform(post(path).with(csrf()).param("username","alice@example.com").param("password","test-password"))
                    .andExpect(unauthenticated()).andReturn().getResponse();
            assertThat(response.getStatus()).isNotEqualTo(200);
        }
        mvc.perform(get("/auth/check")).andExpect(content().string("false"));
    }
}
