package com.TreDL.ecommerce.config;

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

class DatabaseSafetyEnvironmentTests {
    private final DatabaseSafetyEnvironment guard = new DatabaseSafetyEnvironment();

    static MockEnvironment dev() {
        var env = new MockEnvironment(); env.setActiveProfiles("dev");
        Map.of("spring.datasource.url","jdbc:mysql://127.0.0.1:3306/3dl_checkout_dev",
            "spring.datasource.username","3dl_dev","spring.datasource.password","test-only",
            "checkout.quote-secret-base64",Base64.getEncoder().encodeToString(new byte[32]),
            "spring.jpa.hibernate.ddl-auto","validate","spring.jpa.generate-ddl","false",
            "spring.jpa.open-in-view","false","spring.sql.init.mode","never","server.address","127.0.0.1")
            .forEach(env::setProperty);
        Map.of("spring.flyway.enabled","true","spring.flyway.clean-disabled","true",
            "spring.flyway.baseline-on-migrate","false","spring.flyway.create-schemas","false",
            "spring.flyway.validate-on-migrate","true","spring.flyway.locations","classpath:db/checkout-v2")
            .forEach(env::setProperty);
        return env;
    }

    static MockEnvironment test() {
        var env = new MockEnvironment().withProperty("spring.datasource.url","jdbc:h2:mem:startup")
            .withProperty("spring.jpa.hibernate.ddl-auto","create-drop")
            .withProperty("spring.flyway.enabled","false")
            .withProperty("app.data-initializer.enabled","false");
        env.setActiveProfiles("test"); return env;
    }

    @Test void explicitDevAcceptsOnlyTheDedicatedDatabaseAndRechecksLateOverrides() {
        var env=dev(); guard.postProcessEnvironment(env,null);
        DatabaseSafetyEnvironment.validate(env,new DefaultListableBeanFactory());
        env.setProperty("spring.datasource.url","jdbc:mysql://localhost:3306/ecommerce");
        assertThatThrownBy(() -> DatabaseSafetyEnvironment.validate(env,new DefaultListableBeanFactory()))
            .hasMessageContaining("3dl_checkout_dev");
    }

    @ParameterizedTest @ValueSource(strings={"default","unknown","checkout-release","checkout-browser","dev,test"})
    void actualBootstrapRefusesUnsupportedModesBeforeContextPrepared(String profile) {
        var env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        AtomicBoolean prepared=new AtomicBoolean();
        SpringApplication app=new SpringApplication(EcommerceApplication.class);
        app.setEnvironment(env);app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        app.addListeners((ApplicationListener<ApplicationPreparedEvent>) event -> prepared.set(true));
        assertThatThrownBy(() -> {try(var context=app.run("--spring.profiles.active="+profile)) {
            throw new AssertionError("Unsafe startup succeeded");
        }}).isInstanceOf(IllegalStateException.class);
        assertThat(prepared).isFalse();
    }

    @Test void defaultDoesNotConnectEvenWithALegacyUrl() {
        var env=dev();env.setActiveProfiles();env.setProperty("spring.datasource.url","jdbc:mysql://localhost:3306/ecommerce");
        assertThatThrownBy(() -> guard.postProcessEnvironment(env,null)).hasMessageContaining("dev profile");
    }

    @ParameterizedTest @ValueSource(strings={
        "jdbc:mysql://localhost:3306/ecommerce", "jdbc:mysql://db.example:3306/3dl_checkout_dev",
        "jdbc:mysql://127.0.0.1:3306/3dl_checkout_dev_backup", "jdbc:mysql://127.0.0.1:0/3dl_checkout_dev",
        "jdbc:mysql://127.0.0.1:70000/3dl_checkout_dev", "jdbc:h2:file:./data/ecommerce-dev",
        "jdbc:mysql://127.0.0.1:3306/3dl_checkout_dev?sessionVariables=sql_mode=''"
    })
    void devRefusesOtherDatabasesHostsAndConnectionOverrides(String url) {
        var env=dev();env.setProperty("spring.datasource.url",url);
        assertThatThrownBy(() -> guard.postProcessEnvironment(env,null)).hasMessageContaining("3dl_checkout_dev");
    }

    @ParameterizedTest @ValueSource(strings={"update","create","create-drop","none"})
    void devCannotGenerateOrUpdateSchema(String ddl) {
        var env=dev();env.setProperty("spring.jpa.hibernate.ddl-auto",ddl);
        assertThatThrownBy(() -> guard.postProcessEnvironment(env,null)).hasMessageContaining("validate");
    }

    @ParameterizedTest @ValueSource(strings={
        "spring.flyway.clean-disabled=false","spring.flyway.baseline-on-migrate=true",
        "spring.flyway.create-schemas=true","spring.flyway.validate-on-migrate=false",
        "spring.flyway.locations=classpath:db/elsewhere","spring.sql.init.mode=always",
        "spring.jpa.generate-ddl=true","spring.jpa.open-in-view=true","server.address=0.0.0.0"
    })
    void devRefusesUnsafeMigrationAndServerSettings(String property) {
        var env=dev();String[] pair=property.split("=",2);env.setProperty(pair[0],pair[1]);
        assertThatThrownBy(() -> guard.postProcessEnvironment(env,null)).hasMessageContaining("Unsafe dev setting");
    }

    @ParameterizedTest @ValueSource(strings={"spring.datasource.username","spring.datasource.password","checkout.quote-secret-base64"})
    void requiredSettingsCannotBeEmpty(String key) {
        var env=dev();env.setProperty(key,"");
        assertThatThrownBy(() -> guard.postProcessEnvironment(env,null)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings={"not-base64","c2hvcnQ="})
    void invalidHmacCannotStartDev(String key) {
        var env=dev();env.setProperty("checkout.quote-secret-base64",key);
        assertThatThrownBy(() -> guard.postProcessEnvironment(env,null)).isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining(key);
    }

    @Test void rootAccountIsNotADevDefault() {
        var env=dev();env.setProperty("spring.datasource.username","root");
        assertThatThrownBy(() -> guard.postProcessEnvironment(env,null)).hasMessageContaining("non-root");
    }

    @ParameterizedTest @ValueSource(strings={"spring.datasource.hikari.jdbc-url","spring.datasource.jndi-name",
        "spring.jpa.properties.hibernate.connection.url","spring.jpa.properties.hibernate.connection.provider_class",
        "spring.jpa.properties.jakarta.persistence.jdbc.url","spring.flyway.url","spring.flyway.user",
        "spring.datasource.hikari.catalog","spring.datasource.hikari.connection-init-sql",
        "spring.jpa.properties.hibernate.default_catalog"})
    void alternateConnectionSettingsCannotBypassTheDatabasePolicy(String key) {
        var env=dev();env.setProperty(key,"forbidden-fixture");
        assertThatThrownBy(() -> guard.postProcessEnvironment(env,null))
            .hasMessageContaining("Alternate database configuration forbidden").hasMessageNotContaining("forbidden-fixture");
    }

    @ParameterizedTest @ValueSource(strings={"jdbc:mysql://localhost:3306/ecommerce",
        "jdbc:mysql://127.0.0.1:3306/checkout_v2_it_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "jdbc:h2:file:./data/ecommerce-dev","jdbc:h2:mem:startup;INIT=RUNSCRIPT FROM 'forbidden.sql'"})
    void testProfileCannotUseAnArbitraryPersistentDatabase(String url) {
        var env=test();env.setProperty("spring.datasource.url",url);
        assertThatThrownBy(() -> guard.postProcessEnvironment(env,null)).hasMessageContaining("isolated");
    }

    @Test void testMemoryIsAcceptedAndMysqlRequiresTheOwnedDatasource() {
        var env=test();guard.postProcessEnvironment(env,null);
        DatabaseSafetyEnvironment.validate(env,new DefaultListableBeanFactory());
        env.setProperty("spring.datasource.url","jdbc:mysql://127.0.0.1:49001/checkout_v2_it_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        env.setProperty("spring.jpa.hibernate.ddl-auto","validate");
        var factory=new DefaultListableBeanFactory();
        factory.registerBeanDefinition("dataSource",new RootBeanDefinition(Object.class));
        assertThatThrownBy(() -> DatabaseSafetyEnvironment.validate(env,factory)).hasMessageContaining("Owned isolated datasource");
    }

    @ParameterizedTest @ValueSource(strings={"default","test","checkout-release"})
    void demoInitializerCannotRunOutsideDev(String profile) {
        ProductsRepository repository=mock(ProductsRepository.class);
        new ApplicationContextRunner().withUserConfiguration(DataInitializer.class)
            .withBean(ProductsRepository.class,() -> repository)
            .withPropertyValues("spring.profiles.active="+profile,"app.data-initializer.enabled=true")
            .run(c -> {assertThat(c).doesNotHaveBean(DataInitializer.class);verifyNoInteractions(repository);});
    }

    @Test void demoInitializerNeedsAnExplicitOptIn() {
        ProductsRepository repository=mock(ProductsRepository.class);
        var context=new ApplicationContextRunner().withUserConfiguration(DataInitializer.class)
            .withBean(ProductsRepository.class,() -> repository).withPropertyValues("spring.profiles.active=dev");
        context.run(c -> assertThat(c).doesNotHaveBean(DataInitializer.class));
        context.withPropertyValues("app.data-initializer.enabled=true").run(c -> assertThat(c).hasSingleBean(DataInitializer.class));
        verifyNoInteractions(repository);
    }
}
