package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.Customers;
import com.TreDL.ecommerce.repository.CustomersRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @ContextConfiguration(initializers=MySqlCheckoutTestDatabase.Initializer.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PasswordRecoveryMySqlTests {
    @Autowired PasswordResetStore store;
    @Autowired CustomersRepository customers;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    Customers alice;
    @BeforeEach void fixture() {
        jdbc.update("DELETE FROM password_reset_tokens");customers.deleteAll();
        alice=new Customers();alice.setUsername("Alice");alice.setEmail("alice@example.com");alice.setRole("USER");alice.setPassword(passwords.encode("original-pass1"));customers.saveAndFlush(alice);
    }
    <T> List<T> race(Callable<T> operation) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        Callable<T> task=()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Race not released");return operation.call();};
        try {var a=pool.submit(task);var b=pool.submit(task);assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();return List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));}
        finally {start.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void simultaneousRequestsLeaveOnlyOneValidHashAndPreserveTheCustomer() throws Exception {
        var issued=race(()->store.issue(alice.getEmail()));
        assertThat(issued.stream().filter(i->store.valid(i.token)).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens",Integer.class)).isEqualTo(1);
        assertThat(customers.findById(alice.getId()).orElseThrow().getPassword()).isEqualTo(alice.getPassword());
    }
    @Test void simultaneousUseChangesPasswordExactlyOnceAndDeletesAllTokens() throws Exception {
        String token=store.issue(alice.getEmail()).token;
        var results=race(()->{try{store.consume(token,"new-password123","new-password123");return true;}catch(PasswordResetStore.InvalidToken expected){return false;}});
        assertThat(results).containsExactlyInAnyOrder(true,false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens",Integer.class)).isZero();
        assertThat(passwords.matches("new-password123",customers.findById(alice.getId()).orElseThrow().getPassword())).isTrue();
    }
}
