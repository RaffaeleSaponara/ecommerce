package com.TreDL.ecommerce.security;

import com.TreDL.ecommerce.model.Customers;
import com.TreDL.ecommerce.repository.CustomersRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ProfileHttpTests {
    @Autowired MockMvc mvc;
    @Autowired CustomersRepository customers;
    @Autowired PasswordEncoder passwords;
    Customers alice,bob;
    static final String OLD="original-pass1", NEXT="replacement-pass2";
    @BeforeEach void fixtures() {
        customers.deleteAll();alice=create("alice");bob=create("bob");
    }
    Customers create(String name) {
        Customers c=new Customers();c.setUsername(name);c.setEmail(name+"@profile.test");c.setRole("USER");c.setPassword(passwords.encode(OLD));
        return customers.saveAndFlush(c);
    }
    MockHttpSession login(String email,String password) throws Exception {
        return (MockHttpSession)mvc.perform(post("/login").with(csrf()).param("username",email).param("password",password))
                .andExpect(authenticated()).andReturn().getRequest().getSession(false);
    }
    @Test void profileShowsOnlyPublicFieldsAndBothFormsHaveCsrf() throws Exception {
        String body=mvc.perform(get("/user").session(login(alice.getEmail(),OLD)))
                .andExpect(status().isOk()).andExpect(model().attributeExists("profile","orders"))
                .andExpect(content().string(containsString("Il mio profilo")))
                .andExpect(content().string(containsString(alice.getEmail())))
                .andExpect(content().string(containsString("value=\"alice\"")))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(alice.getPassword(),bob.getEmail(),OLD);
        assertThat(body).contains("action=\"/user/profile\"","action=\"/user/password\"","name=\"_csrf\"");
        assertThat(body).doesNotContain("name=\"email\"","name=\"userId\"");
    }
    @Test void usernameUpdatesOnlyCurrentAccountAndKeepsEmailAndSession() throws Exception {
        var session=login(alice.getEmail(),OLD);
        mvc.perform(post("/user/profile").session(session).with(csrf()).param("username","  NewAlice  ")
                .param("id",bob.getId().toString()).param("email",bob.getEmail()).param("role","ADMIN"))
                .andExpect(redirectedUrl("/user?profileSaved#my-profile"));
        var changed=customers.findById(alice.getId()).orElseThrow();
        assertThat(changed.getUsername()).isEqualTo("NewAlice");assertThat(changed.getEmail()).isEqualTo(alice.getEmail());
        assertThat(changed.getRole()).isEqualTo("USER");assertThat(changed.getPassword()).isEqualTo(alice.getPassword());
        assertThat(customers.findById(bob.getId()).orElseThrow().getUsername()).isEqualTo("bob");
        mvc.perform(get("/user").session(session).param("profileSaved",""))
                .andExpect(authenticated()).andExpect(content().string(containsString("Profilo aggiornato correttamente")));
    }
    @Test void duplicateUsernameAndInvalidNameAreFriendlyAndUnchanged() throws Exception {
        mvc.perform(post("/user/profile").with(user(alice.getEmail())).with(csrf()).param("username","bob"))
                .andExpect(redirectedUrl("/user?profileError=username#account-settings"));
        mvc.perform(post("/user/profile").with(user(alice.getEmail())).with(csrf()).param("username"," "))
                .andExpect(redirectedUrl("/user?profileError=usernameInvalid#account-settings"));
        assertThat(customers.findById(alice.getId()).orElseThrow().getUsername()).isEqualTo("alice");
    }
    @Test void passwordChangeCommitsHashLogsOutAndRequiresNewCredentials() throws Exception {
        var session=login(alice.getEmail(),OLD);
        mvc.perform(post("/user/password").session(session).with(csrf()).param("currentPassword",OLD)
                .param("newPassword",NEXT).param("confirmPassword",NEXT).param("id",bob.getId().toString()))
                .andExpect(redirectedUrl("/login?passwordChanged")).andExpect(unauthenticated());
        assertThat(session.isInvalid()).isTrue();
        String hash=customers.findById(alice.getId()).orElseThrow().getPassword();
        assertThat(hash).startsWith("$2").isNotEqualTo(NEXT).isNotEqualTo(alice.getPassword());
        assertThat(passwords.matches(NEXT,hash)).isTrue();assertThat(passwords.matches(OLD,hash)).isFalse();
        assertThat(customers.findById(bob.getId()).orElseThrow().getPassword()).isEqualTo(bob.getPassword());
        mvc.perform(post("/login").with(csrf()).param("username",alice.getEmail()).param("password",OLD)).andExpect(unauthenticated());
        login(alice.getEmail(),NEXT);
        mvc.perform(get("/user")).andExpect(status().isUnauthorized());
        mvc.perform(get("/login").param("passwordChanged",""))
                .andExpect(content().string(containsString("Password aggiornata correttamente")));
    }
    static Stream<Arguments> invalidPasswords() {
        return Stream.of(Arguments.of("wrong",NEXT,NEXT,"currentPassword"),Arguments.of("",NEXT,NEXT,"currentPassword"),Arguments.of(OLD,"","","passwordInvalid"),
                Arguments.of(OLD,"short1","short1","passwordInvalid"),Arguments.of(OLD,"onlyletterslong","onlyletterslong","passwordInvalid"),
                Arguments.of(OLD,"123456789012","123456789012","passwordInvalid"),
                Arguments.of(OLD,"é".repeat(36)+"1","é".repeat(36)+"1","passwordInvalid"),
                Arguments.of(OLD,NEXT,"different-pass3","password"),Arguments.of(OLD,OLD,OLD,"passwordReused"));
    }
    @ParameterizedTest @MethodSource("invalidPasswords")
    void rejectedPasswordKeepsHashAndAuthenticatedSession(String current,String next,String confirm,String code) throws Exception {
        var session=login(alice.getEmail(),OLD);
        mvc.perform(post("/user/password").session(session).with(csrf()).param("currentPassword",current)
                .param("newPassword",next).param("confirmPassword",confirm))
                .andExpect(redirectedUrl("/user?profileError="+code+"#account-settings"));
        assertThat(session.isInvalid()).isFalse();assertThat(customers.findById(alice.getId()).orElseThrow().getPassword()).isEqualTo(alice.getPassword());
        mvc.perform(get("/user").session(session)).andExpect(authenticated());
    }
    @Test void anonymousAndMissingCsrfCannotWrite() throws Exception {
        for(String path:new String[]{"/user/profile","/user/password"}) {
            mvc.perform(post(path).with(csrf())).andExpect(status().isUnauthorized());
            mvc.perform(post(path).with(user(alice.getEmail()))).andExpect(status().isForbidden());
        }
        assertThat(customers.findById(alice.getId()).orElseThrow().getPassword()).isEqualTo(alice.getPassword());
    }
}
