package com.TreDL.ecommerce.service;
import com.TreDL.ecommerce.model.Customers;
import com.TreDL.ecommerce.repository.CustomersRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @ContextConfiguration(initializers=MySqlCheckoutTestDatabase.Initializer.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class EmailVerificationMySqlTests {
    @Autowired EmailVerificationStore store;
    @Autowired CustomersRepository customers;
    @Autowired JdbcTemplate jdbc;
    @Test void concurrentConfirmationIsSingleUseAndConcurrentResendsReplacePreviousToken() throws Exception {
        Customers c=new Customers();c.setUsername("Pending");c.setEmail("pending@example.com");c.setPassword("fixture-hash");c.setRole("USER");c.setEmailVerified(false);customers.saveAndFlush(c);
        var issued=race(()->store.issue(c.getEmail()));
        byte[] current=jdbc.queryForObject("SELECT token_hash FROM email_verification_tokens WHERE id_customer=?",byte[].class,c.getId());
        assertThat(issued.stream().filter(t->Arrays.equals(PasswordResetStore.hash(t.token),current)).count()).isEqualTo(1);
        String token=issued.stream().filter(t->Arrays.equals(PasswordResetStore.hash(t.token),current)).findFirst().orElseThrow().token;
        var results=race(()->{try{store.confirm(token);return true;}catch(EmailVerificationStore.LinkException e){return false;}});
        assertThat(results).containsExactlyInAnyOrder(true,false);
        assertThat(customers.findById(c.getId()).orElseThrow().isEmailVerified()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_verification_tokens",Integer.class)).isZero();
    }
    <T> List<T> race(Callable<T> action) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        Callable<T> task=()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Race not released");return action.call();};
        try{var a=pool.submit(task);var b=pool.submit(task);assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();return List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));}
        finally{start.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
}
