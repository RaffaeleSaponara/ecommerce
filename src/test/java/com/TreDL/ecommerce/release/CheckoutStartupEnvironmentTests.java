package com.TreDL.ecommerce.release;

import com.TreDL.ecommerce.DataInitializer;
import com.TreDL.ecommerce.EcommerceApplication;
import com.TreDL.ecommerce.repository.ProductsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.*;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CheckoutStartupEnvironmentTests {
    private final CheckoutStartupEnvironment guard = new CheckoutStartupEnvironment();

    static MockEnvironment isolatedTest() {
        return new MockEnvironment().withProperty("spring.datasource.url", "jdbc:h2:mem:startup")
            .withProperty("spring.jpa.hibernate.ddl-auto", "create-drop")
            .withProperty("spring.flyway.enabled", "false")
            .withProperty("app.data-initializer.enabled", "false");
    }

    @Test void defaultIsRejectedEvenWithExplicitLegacyDatasource() {
        var env = new MockEnvironment();
        assertThatThrownBy(() -> guard.postProcessEnvironment(env, null))
            .hasMessageContaining("Explicit Checkout V2 startup required");
        env.setProperty("spring.datasource.url", "jdbc:mysql://localhost:3306/ecommerce");
        assertThatThrownBy(() -> guard.postProcessEnvironment(env, null))
            .hasMessageContaining("Explicit Checkout V2 startup required");
    }

    @ParameterizedTest @ValueSource(strings={"default", "dev", "unknown", "checkout-browser"})
    void actualBootstrapRejectsUnsupportedModesBeforeContextPrepared(String profile) {
        var env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("safe-negative-fixture",
            Map.of("spring.datasource.url", "jdbc:h2:mem:rejected_"+profile.replace('-','_'))));
        AtomicBoolean prepared = new AtomicBoolean();
        SpringApplication app = new SpringApplication(EcommerceApplication.class);
        app.setEnvironment(env);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        app.addListeners((ApplicationListener<ApplicationPreparedEvent>) event -> prepared.set(true));
        assertThatThrownBy(() -> { try (var unexpected = app.run("--spring.profiles.active="+profile)) {
            throw new AssertionError("Unsafe startup succeeded");
        }}).isInstanceOf(IllegalStateException.class);
        assertThat(prepared).isFalse();
    }

    @ParameterizedTest @ValueSource(strings={
        "jdbc:mysql://localhost:3306/ecommerce",
        "jdbc:mysql://127.0.0.1:3306/checkout_v2_it_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "jdbc:h2:file:./data/ecommerce-dev",
        "jdbc:h2:mem:startup;INIT=RUNSCRIPT FROM 'forbidden.sql'"
    })
    void testProfileCannotSelectArbitraryOrPersistentDatabase(String url) {
        var env = isolatedTest(); env.setActiveProfiles("test"); env.setProperty("spring.datasource.url",url);
        assertThatThrownBy(() -> guard.postProcessEnvironment(env,null)).hasMessageContaining("isolated");
    }

    @Test void inMemoryTestModeIsAcceptedAndLateDestinationOverrideIsRejected() {
        var env = isolatedTest();env.setActiveProfiles("test");
        guard.postProcessEnvironment(env,null);
        CheckoutStartupEnvironment.validateFinal(env,false,new DefaultListableBeanFactory());
        env.setProperty("spring.datasource.url","jdbc:mysql://localhost:3306/ecommerce");
        assertThatThrownBy(() -> CheckoutStartupEnvironment.validateFinal(env,false,new DefaultListableBeanFactory()))
            .hasMessageContaining("isolated");
    }

    @Test void ownedMysqlNameAloneDoesNotAttestItsDatasource() {
        var env = isolatedTest();env.setActiveProfiles("test");
        env.setProperty("spring.datasource.url","jdbc:mysql://127.0.0.1:49001/checkout_v2_it_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        env.setProperty("spring.jpa.hibernate.ddl-auto","validate");
        var factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("dataSource",new RootBeanDefinition(Object.class));
        assertThatThrownBy(() -> CheckoutStartupEnvironment.validateFinal(env,false,factory))
            .hasMessageContaining("Owned isolated datasource");
    }

    @ParameterizedTest @ValueSource(strings={"update","create","create-drop","none"})
    void releaseSchemaOverridesAreRejected(String ddl) throws Exception {
        var env = ReleaseEnvironmentTests.environment(ReleaseEnvironmentTests.values());
        env.setProperty("spring.jpa.hibernate.ddl-auto",ddl);
        assertThatThrownBy(() -> guard.postProcessEnvironment(env,null)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings={
        "spring.datasource.hikari.jdbc-url",
        "spring.datasource.jndi-name",
        "spring.jpa.properties.hibernate.connection.url",
        "spring.jpa.properties.hibernate.connection.provider_class",
        "spring.jpa.properties.jakarta.persistence.jdbc.url",
        "spring.jpa.properties.javax.persistence.jdbc.url"
    })
    void alternateProvidersCannotBypassDestinationPolicy(String setting) {
        var env = isolatedTest();env.setActiveProfiles("test");env.setProperty(setting,"forbidden-fixture");
        assertThatThrownBy(() -> guard.postProcessEnvironment(env,null))
            .hasMessageContaining("Alternate database configuration forbidden").hasMessageNotContaining("forbidden-fixture");
    }

    @Test void validExplicitReleaseKeepsExistingProtections() throws Exception {
        var env = ReleaseEnvironmentTests.environment(ReleaseEnvironmentTests.values());
        guard.postProcessEnvironment(env,null);
        CheckoutStartupEnvironment.validateFinal(env,false,new DefaultListableBeanFactory());
        ReleaseEnvironment.INVARIANTS.forEach((key,value) -> assertThat(env.getProperty(key)).isEqualTo(value));
        env.setProperty("spring.jpa.properties.hibernate.connection.url","jdbc:mysql://localhost:3306/ecommerce");
        assertThatThrownBy(() -> CheckoutStartupEnvironment.validateFinal(env,false,new DefaultListableBeanFactory()))
            .hasMessageContaining("Alternate database configuration forbidden");
    }

    @ParameterizedTest @ValueSource(strings={"default","dev","test","checkout-release"})
    void initializerCannotBeEnabledOutsideDevelopmentEvenByProperty(String profile) {
        ProductsRepository repository = mock(ProductsRepository.class);
        new ApplicationContextRunner().withUserConfiguration(DataInitializer.class)
            .withBean(ProductsRepository.class,() -> repository)
            .withPropertyValues("spring.profiles.active="+profile,"app.data-initializer.enabled=true")
            .run(context -> {assertThat(context).doesNotHaveBean(DataInitializer.class);verifyNoInteractions(repository);});
    }

    @Test void initializerRequiresExplicitDevelopmentOptIn() {
        ProductsRepository repository = mock(ProductsRepository.class);
        var context = new ApplicationContextRunner().withUserConfiguration(DataInitializer.class)
            .withBean(ProductsRepository.class,() -> repository)
            .withPropertyValues("spring.profiles.active=checkout-browser");
        context.run(c -> assertThat(c).doesNotHaveBean(DataInitializer.class));
        context.withPropertyValues("app.data-initializer.enabled=true")
            .run(c -> assertThat(c).hasSingleBean(DataInitializer.class));
        verifyNoInteractions(repository);
    }
}
