package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.Customers;
import com.TreDL.ecommerce.repository.CustomersRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest(properties="app.mail.enabled=true") @AutoConfigureMockMvc @ActiveProfiles("test")
@Import(PasswordRecoveryTests.TestTime.class) @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PasswordRecoveryTests {
    static class Time extends Clock {
        Instant now=Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration d){now=now.plus(d);} public Instant instant(){return now;}
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId z){return this;}
    }
    @TestConfiguration static class TestTime {@Bean @Primary Time time(){return new Time();}}
    @Autowired Time time;
    @Autowired MockMvc mvc;
    @Autowired CustomersRepository customers;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordResetStore store;
    @Autowired PasswordRecoveryService recovery;
    @MockitoBean JavaMailSender smtp;
    final List<SimpleMailMessage> messages=new CopyOnWriteArrayList<>();
    Customers alice;
    static final String OLD="original-pass1",NEXT="new-password123";
    @BeforeEach void fixture() {
        time.advance(Duration.ofDays(1));messages.clear();jdbc.update("DELETE FROM password_reset_tokens");customers.deleteAll();
        alice=new Customers();alice.setUsername("Alice");alice.setEmail("alice@example.com");alice.setRole("USER");alice.setPassword(passwords.encode(OLD));customers.saveAndFlush(alice);
        doAnswer(invocation->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();messages.add(invocation.getArgument(0));return null;})
                .when(smtp).send(any(SimpleMailMessage.class));
    }
    String request(String email) throws Exception {
        mvc.perform(post("/forgot-password").with(csrf()).header("Host","attacker.invalid").param("email",email)).andExpect(redirectedUrl("/forgot-password?sent"));
        if(messages.isEmpty())return null;
        var match=Pattern.compile("token=([A-Za-z0-9_-]{43})").matcher(messages.get(messages.size()-1).getText());
        assertThat(match.find()).isTrue();return match.group(1);
    }
    @Test void existingAndUnknownEmailsHaveSameResponseAndOnlyHashesAreStored() throws Exception {
        request("missing@example.com");assertThat(messages).isEmpty();
        String token=request(" ALICE@EXAMPLE.COM ");
        assertThat(messages).hasSize(1);assertThat(messages.get(0).getTo()).containsExactly("alice@example.com");
        assertThat(messages.get(0).getText()).contains("http://127.0.0.1:9085/reset-password?token=").doesNotContain("attacker.invalid");
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM password_reset_tokens",byte[].class)).isEqualTo(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        assertThat(jdbc.queryForObject("SELECT expires_at FROM password_reset_tokens",java.sql.Timestamp.class).toLocalDateTime()).isEqualTo(java.time.LocalDateTime.ofInstant(time.instant(),ZoneOffset.UTC).plusMinutes(30));
        mvc.perform(get("/forgot-password").param("sent","" )).andExpect(content().string(containsString("Se l’indirizzo corrisponde a un account")));
    }
    @Test void formDoesNotConsumeTokenAndAppliesPrivacyHeaders() throws Exception {
        String token=request(alice.getEmail());
        String html=mvc.perform(get("/reset-password").param("token",token)).andExpect(status().isOk())
                .andExpect(header().string("Referrer-Policy","no-referrer"))
                .andExpect(header().string("Cache-Control",containsString("no-store")))
                .andExpect(header().string("Content-Security-Policy",containsString("form-action 'self'")))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("name=\"token\"","name=\"_csrf\"").doesNotContain("fonts.googleapis.com","analytics");
        assertThat(store.valid(token)).isTrue();
        mvc.perform(post("/reset-password").param("token",token)).andExpect(status().isForbidden()).andExpect(header().string("Referrer-Policy","no-referrer"));
    }
    @Test void newRequestInvalidatesPreviousLinkAndTamperedOrExpiredLinksCannotChangePassword() throws Exception {
        String first=request(alice.getEmail()),second=request(alice.getEmail());
        assertThat(store.valid(first)).isFalse();assertThat(store.valid(second)).isTrue();
        String tampered=(second.charAt(0)=='A'?"B":"A")+second.substring(1);
        for(String token:List.of(first,tampered,"bad-token"))assertThatThrownBy(()->recovery.reset(token,NEXT,NEXT)).isInstanceOf(PasswordResetStore.InvalidToken.class);
        time.advance(Duration.ofMinutes(30));
        assertThatThrownBy(()->recovery.reset(second,NEXT,NEXT)).isInstanceOf(PasswordResetStore.InvalidToken.class);
        assertThat(customers.findById(alice.getId()).orElseThrow().getPassword()).isEqualTo(alice.getPassword());
    }
    @Test void invalidPasswordsKeepTokenAndPasswordUnchanged() throws Exception {
        String token=request(alice.getEmail());
        for(String invalid:List.of("","short1","onlyletterslong","123456789012","é".repeat(36)+"1"))
            assertThatThrownBy(()->recovery.reset(token,invalid,invalid)).isInstanceOf(RegistrationService.InvalidRegistration.class);
        assertThatThrownBy(()->recovery.reset(token,NEXT,"mismatch1234")).isInstanceOf(RegistrationService.InvalidRegistration.class);
        assertThat(store.valid(token)).isTrue();assertThat(customers.findById(alice.getId()).orElseThrow().getPassword()).isEqualTo(alice.getPassword());
    }
    MockHttpSession login(String password) throws Exception {
        return (MockHttpSession)mvc.perform(post("/login").with(csrf()).param("username",alice.getEmail()).param("password",password))
                .andExpect(authenticated()).andReturn().getRequest().getSession(false);
    }
    @Test void successfulResetIsSingleUseNotifiesAndRevokesExistingLocalSessions() throws Exception {
        var session=login(OLD);String token=request(alice.getEmail());
        mvc.perform(post("/reset-password").with(csrf()).param("token",token).param("password",NEXT).param("confirmPassword",NEXT))
                .andExpect(redirectedUrl("/login?passwordReset")).andExpect(unauthenticated());
        assertThat(store.valid(token)).isFalse();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens",Integer.class)).isZero();
        assertThatThrownBy(()->recovery.reset(token,"another-password1","another-password1")).isInstanceOf(PasswordResetStore.InvalidToken.class);
        String hash=customers.findById(alice.getId()).orElseThrow().getPassword();assertThat(passwords.matches(NEXT,hash)).isTrue();assertThat(passwords.matches(OLD,hash)).isFalse();
        assertThat(messages).hasSize(2);assertThat(messages.get(1).getText()).doesNotContain(token,OLD,NEXT,"token=");
        mvc.perform(get("/user").session(session)).andExpect(redirectedUrl("/login?sessionExpired"));assertThat(session.isInvalid()).isTrue();
        mvc.perform(post("/login").with(csrf()).param("username",alice.getEmail()).param("password",OLD)).andExpect(unauthenticated());login(NEXT);
    }
    @Test void limitsAndSmtpFailuresHaveGenericResponse() throws Exception {
        for(int i=0;i<4;i++)request(alice.getEmail());assertThat(messages).hasSize(3);
        time.advance(Duration.ofMinutes(15));
        doThrow(new MailSendException("do not expose transport details")).when(smtp).send(any(SimpleMailMessage.class));
        mvc.perform(post("/forgot-password").with(csrf()).param("email",alice.getEmail())).andExpect(redirectedUrl("/forgot-password?sent"));
        assertThat(customers.findById(alice.getId()).orElseThrow().getPassword()).isEqualTo(alice.getPassword());
    }
    @Test void authenticatedPasswordChangeAlsoRevokesRecoveryToken() throws Exception {
        String token=request(alice.getEmail());
        mvc.perform(post("/user/password").session(login(OLD)).with(csrf()).param("currentPassword",OLD).param("newPassword",NEXT).param("confirmPassword",NEXT))
                .andExpect(redirectedUrl("/login?passwordChanged"));assertThat(store.valid(token)).isFalse();
    }
    @Test void notificationFailureDoesNotUndoCommittedPasswordReset() throws Exception {
        String token=request(alice.getEmail());
        doThrow(new MailSendException("do not expose transport details")).when(smtp).send(any(SimpleMailMessage.class));
        recovery.reset(token,NEXT,NEXT);
        assertThat(passwords.matches(NEXT,customers.findById(alice.getId()).orElseThrow().getPassword())).isTrue();
        assertThat(store.valid(token)).isFalse();
    }
}
