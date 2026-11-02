package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.repository.CustomersRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@ContextConfiguration(initializers=MySqlCheckoutTestDatabase.Initializer.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RegistrationMySqlTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CustomersRepository customers;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;
    @BeforeEach void clear(){ customers.deleteAll(); }
    @Test void caseInsensitiveLoginPreservesHistoricalEmailAndUniqueIndexRejectsVariants() throws Exception {
        jdbc.update("INSERT INTO customers(username,email,password,role,email_verified) VALUES(?,?,?,?,TRUE)","Legacy","  Legacy@Example.com  ",passwords.encode("old-password"),"USER");
        mvc.perform(post("/login").with(csrf()).param("username"," LEGACY@example.COM ").param("password","old-password"))
                .andExpect(status().is3xxRedirection()).andExpect(authenticated());
        assertThat(jdbc.queryForObject("SELECT email FROM customers WHERE username='Legacy'",String.class)).isEqualTo("  Legacy@Example.com  ");
        assertThatThrownBy(()->jdbc.update("INSERT INTO customers(username,email,password,role) VALUES('Other','legacy@example.com','hash','USER')"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(customers.count()).isEqualTo(1);
    }
    @Test void simultaneousEmailsCreateOneAccountAndGiveFriendlyConflict() throws Exception {
        race("First","SECOND@example.com","Second"," second@EXAMPLE.COM ","email");
    }
    @Test void simultaneousUsernamesCreateOneAccountAndGiveFriendlyConflict() throws Exception {
        race("SameName","one@example.com","SameName","two@example.com","username");
    }
    void race(String name1,String email1,String name2,String email2,String code) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try {
            var first=pool.submit(()->signup(name1,email1,ready,start));
            var second=pool.submit(()->signup(name2,email2,ready,start));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("/login?registered",code.equals("email")?"/login?registered":"/registrazione?error="+code);
            assertThat(customers.count()).isEqualTo(1);
            assertThat(customers.findAll().get(0).getRole()).isEqualTo("USER");
        } finally {start.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
    String signup(String name,String email,CountDownLatch ready,CountDownLatch start) throws Exception {
        ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Race not released");
        return mvc.perform(post("/signup").with(csrf()).param("username",name).param("email",email)
                .param("password","good-password1").param("confirmPassword","good-password1").param("role","ADMIN"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
    }
}
