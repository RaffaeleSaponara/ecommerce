package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.Customers;
import com.TreDL.ecommerce.repository.CustomersRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.*;
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
class EmailVerificationTests {
    @Autowired MockMvc mvc;
    @Autowired CustomersRepository customers;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordRecoveryTests.Time time;
    @Autowired PasswordEncoder passwords;
    @Autowired EmailVerificationStore store;
    @MockitoBean JavaMailSender smtp;
    final List<SimpleMailMessage> messages=new ArrayList<>();
    @BeforeEach void fixture() {
        time.advance(Duration.ofDays(2));messages.clear();jdbc.update("DELETE FROM email_verification_tokens");jdbc.update("DELETE FROM password_reset_tokens");customers.deleteAll();
        doAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();messages.add(i.getArgument(0));return null;}).when(smtp).send(any(SimpleMailMessage.class));
    }
    void signup() throws Exception {
        mvc.perform(post("/signup").with(csrf()).param("username","Alice").param("email"," ALICE@example.com ")
                .param("password","good-password1").param("confirmPassword","good-password1").param("role","ADMIN").param("emailVerified","true"))
                .andExpect(redirectedUrl("/login?registered")).andExpect(unauthenticated());
    }
    String token(){var m=Pattern.compile("token=([A-Za-z0-9_-]{43})").matcher(messages.get(messages.size()-1).getText());assertThat(m.find()).isTrue();return m.group(1);}
    void resend(String email) throws Exception {mvc.perform(post("/resend-verification").with(csrf()).param("email",email)).andExpect(redirectedUrl("/resend-verification?sent"));}
    @Test void signupSendsAfterCommitAndOnlyVerificationAllowsLoginWithoutAutoLogin() throws Exception {
        signup();Customers c=customers.findByEmail("alice@example.com").orElseThrow();
        assertThat(c.isEmailVerified()).isFalse();assertThat(c.getRole()).isEqualTo("USER");assertThat(messages).hasSize(1);
        String token=token();assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM email_verification_tokens",byte[].class)).isEqualTo(PasswordResetStore.hash(token));
        assertThat(jdbc.queryForObject("SELECT expires_at FROM email_verification_tokens",java.sql.Timestamp.class).toLocalDateTime()).isEqualTo(LocalDateTime.ofInstant(time.instant(),ZoneOffset.UTC).plusHours(24));
        mvc.perform(post("/login").with(csrf()).param("username",c.getEmail()).param("password","good-password1")).andExpect(redirectedUrl("/login?error")).andExpect(unauthenticated());
        String html=mvc.perform(get("/verify-email").param("token",token)).andExpect(header().string("Referrer-Policy","no-referrer"))
                .andExpect(header().string("Cache-Control",containsString("no-store"))).andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("fonts.googleapis.com","analytics");assertThat(customers.findById(c.getId()).orElseThrow().isEmailVerified()).isFalse();
        mvc.perform(post("/verify-email").param("token",token)).andExpect(status().isForbidden());
        mvc.perform(post("/verify-email").with(csrf()).param("token",token)).andExpect(redirectedUrl("/login?emailVerified")).andExpect(unauthenticated());
        assertThat(customers.findById(c.getId()).orElseThrow().isEmailVerified()).isTrue();
        mvc.perform(post("/login").with(csrf()).param("username",c.getEmail()).param("password","good-password1")).andExpect(authenticated());
        assertThatThrownBy(()->store.confirm(token)).isInstanceOf(EmailVerificationStore.LinkException.class);
    }
    @Test void resendReplacesTokenAndExpiredOrTamperedLinksCannotVerify() throws Exception {
        signup();String first=token();resend("alice@example.com");String second=token();
        assertThat(first).isNotEqualTo(second);
        for(String invalid:List.of(first,"bad",(second.charAt(0)=='A'?"B":"A")+second.substring(1)))
            assertThatThrownBy(()->store.confirm(invalid)).isInstanceOf(EmailVerificationStore.LinkException.class);
        time.advance(Duration.ofHours(24));
        mvc.perform(post("/verify-email").with(csrf()).param("token",second)).andExpect(content().string(containsString("Link scaduto")));
        assertThat(customers.findByEmail("alice@example.com").orElseThrow().isEmailVerified()).isFalse();
    }
    @Test void unknownVerifiedAndThrottledRequestsHaveSameResponse() throws Exception {
        resend("missing@example.com");assertThat(messages).isEmpty();signup();
        resend("alice@example.com");resend("ALICE@example.com");resend("alice@example.com");assertThat(messages).hasSize(3);
        store.confirm(token());time.advance(Duration.ofMinutes(15));resend("alice@example.com");assertThat(messages).hasSize(3);
        mvc.perform(post("/signup").with(csrf()).param("username","Other").param("email","alice@example.com").param("password","good-password1").param("confirmPassword","good-password1"))
                .andExpect(redirectedUrl("/login?registered"));assertThat(messages).hasSize(3);
    }
    @Test void smtpFailureLeavesPendingAccountRecoverableByResend() throws Exception {
        doThrow(new MailSendException("private detail")).when(smtp).send(any(SimpleMailMessage.class));signup();
        assertThat(customers.findByEmail("alice@example.com").orElseThrow().isEmailVerified()).isFalse();
        doAnswer(i->{messages.add(i.getArgument(0));return null;}).when(smtp).send(any(SimpleMailMessage.class));resend("alice@example.com");
        store.confirm(token());assertThat(customers.findByEmail("alice@example.com").orElseThrow().isEmailVerified()).isTrue();
    }
    @Test void passwordRecoveryDoesNotBypassVerification() throws Exception {
        signup();mvc.perform(post("/forgot-password").with(csrf()).param("email","alice@example.com"));String reset=token();
        mvc.perform(post("/reset-password").with(csrf()).param("token",reset).param("password","replacement123").param("confirmPassword","replacement123"))
                .andExpect(redirectedUrl("/login?passwordReset"));
        mvc.perform(post("/login").with(csrf()).param("username","alice@example.com").param("password","replacement123")).andExpect(unauthenticated());
        assertThat(customers.findByEmail("alice@example.com").orElseThrow().isEmailVerified()).isFalse();
    }
}
