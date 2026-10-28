package com.TreDL.ecommerce.release;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.env.PropertiesPropertySource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ReleaseEnvironmentTests {
    static Map<String,String> values() {
        Map<String,String> v=new HashMap<>();
        v.put("CHECKOUT_V2_DB_URL","jdbc:mysql://127.0.0.1:49001/checkoutv2unit?sslMode=REQUIRED");
        v.put("CHECKOUT_V2_DB_NAME","checkoutv2unit"); v.put("CHECKOUT_V2_DB_SERVER_UUID","11111111-1111-1111-1111-111111111111");
        v.put("CHECKOUT_V2_DB_USERNAME","runtime_fixture"); v.put("CHECKOUT_V2_DB_PASSWORD","public-fixture-password");
        v.put("CHECKOUT_QUOTE_SECRET_BASE64",Base64.getEncoder().encodeToString(new byte[32])); return v;
    }
    static MockEnvironment environment(Map<String,String> values) throws Exception {
        MockEnvironment e=new MockEnvironment(); e.setActiveProfiles(ReleaseDatabase.PROFILE);
        Properties p=new Properties(); try(var resource=ReleaseEnvironmentTests.class.getResourceAsStream("/application-checkout-release.properties")){p.load(resource);}
        e.getPropertySources().addLast(new PropertiesPropertySource("release-file",p)); values.forEach(e::setProperty); return e;
    }
    @Test void completeConfigurationPinsSafeRuntimeInvariants() throws Exception {
        var e=environment(values()); new ReleaseEnvironment().postProcessEnvironment(e,null);
        ReleaseEnvironment.INVARIANTS.forEach((k,v)->assertThat(e.getProperty(k)).isEqualTo(v));
        assertThat(e.getProperty("spring.datasource.url")).contains("checkoutv2unit").doesNotContain("ecommerce");
        assertThat(ReleaseDatabase.target(e::getProperty).toString()).doesNotContain("public-fixture-password");
    }
    @ParameterizedTest @ValueSource(strings={"CHECKOUT_V2_DB_URL","CHECKOUT_V2_DB_NAME","CHECKOUT_V2_DB_SERVER_UUID","CHECKOUT_V2_DB_USERNAME","CHECKOUT_V2_DB_PASSWORD","CHECKOUT_QUOTE_SECRET_BASE64"})
    void missingProtectedSettingFailsBeforeDatasourceCreation(String setting) throws Exception {
        var v=values();v.remove(setting);var e=environment(v);
        assertThatThrownBy(()->new ReleaseEnvironment().postProcessEnvironment(e,null)).isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest @ValueSource(strings={"default","dev","checkout-browser","test"})
    void releaseProfileCannotBeCombined(String profile) throws Exception {
        var e=environment(values());e.setActiveProfiles(ReleaseDatabase.PROFILE,profile);
        assertThatThrownBy(()->new ReleaseEnvironment().postProcessEnvironment(e,null)).hasMessage("Release profile must be used alone");
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"not-base64-sensitive-marker!","c2hvcnQ="})
    void invalidHmacIsRejectedWithoutEchoingValues(String secret) {
        assertThatThrownBy(()->ReleaseDatabase.hmac(secret)).isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining("not-base64-sensitive-marker").hasMessageNotContaining("c2hvcnQ=");
    }
    @Test void acceptsAtLeast32Bytes() { ReleaseDatabase.hmac(Base64.getEncoder().encodeToString(new byte[32]));ReleaseDatabase.hmac(Base64.getEncoder().encodeToString(new byte[64])); }
    @ParameterizedTest @ValueSource(strings={
        "jdbc:mysql://localhost:3306/ecommerce", "jdbc:mysql://remote.example:3306/ecommerce?sslMode=REQUIRED",
        "jdbc:h2:mem:ecommerce", "jdbc:mysql://db.example.invalid:3306/checkoutv2unit?sslMode=REQUIRED", "jdbc:mysql://127.0.0.1:49001/checkoutv2unit?sslMode=DISABLED",
        "jdbc:mysql://127.0.0.1:49001/checkoutv2unit", "jdbc:mysql://127.0.0.1:49001/checkoutv2unit?sslMode=REQUIRED&createDatabaseIfNotExist=true",
        "jdbc:mysql://127.0.0.1:49001/checkoutv2unit?sslMode=REQUIRED&password=private",
        "jdbc:mysql://127.0.0.1:49001/checkoutv2unit?sslMode=REQUIRED&sessionVariables=x",
        "jdbc:mysql://user:pass@127.0.0.1:49001/checkoutv2unit?sslMode=REQUIRED",
        "jdbc:mysql://127.0.0.1:49001/checkoutv2unit?sslMode=REQUIRED&sslMode=REQUIRED"})
    void unsafeDestinationsNeverReachJdbc(String url) { var v=values();v.put("CHECKOUT_V2_DB_URL",url);assertThatThrownBy(()->ReleaseDatabase.target(v::get)).isInstanceOf(IllegalStateException.class); }
    @ParameterizedTest @CsvSource({"spring.jpa.hibernate.ddl-auto,update","spring.jpa.properties.hibernate.hbm2ddl.auto,create-drop","spring.flyway.enabled,true","app.data-initializer.enabled,true","spring.h2.console.enabled,true","server.servlet.session.cookie.secure,false"})
    void unsafeOverridesFailRatherThanStart(String key,String value) throws Exception {
        var e=environment(values());e.setProperty(key,value);
        assertThatThrownBy(()->new ReleaseEnvironment().postProcessEnvironment(e,null)).hasMessage("Unsafe release setting: "+key);
    }
    @Test void applicationRejectsMigrationCredentials() throws Exception {
        var e=environment(values());e.setProperty("CHECKOUT_V2_MIGRATION_PASSWORD","protected-fixture");
        assertThatThrownBy(()->new ReleaseEnvironment().postProcessEnvironment(e,null)).hasMessageNotContaining("protected-fixture");
    }
    @Test void rejectsSameMigrationAccountOrSamePassword() {
        var v=values();var target=ReleaseDatabase.target(v::get);
        v.put("CHECKOUT_V2_MIGRATION_USERNAME",v.get("CHECKOUT_V2_DB_USERNAME"));v.put("CHECKOUT_V2_MIGRATION_PASSWORD","another-fixture");
        assertThatThrownBy(()->target.migrationSource(v::get)).hasMessage("Distinct migration credentials required");
        v.put("CHECKOUT_V2_MIGRATION_USERNAME","migration_fixture");v.put("CHECKOUT_V2_MIGRATION_PASSWORD",v.get("CHECKOUT_V2_DB_PASSWORD"));
        assertThatThrownBy(()->target.migrationSource(v::get)).hasMessage("Distinct migration credentials required");
    }
    @ParameterizedTest @ValueSource(strings={"missing", "not-base64-sensitive-marker!", "c2hvcnQ="})
    void actualSpringBootstrapRejectsInvalidSecretBeforeContextIsPrepared(String value) throws Exception {
        var v=values();if(value.equals("missing"))v.remove("CHECKOUT_QUOTE_SECRET_BASE64");else v.put("CHECKOUT_QUOTE_SECRET_BASE64",value);
        var e=environment(v);var prepared=new java.util.concurrent.atomic.AtomicBoolean();
        var app=new org.springframework.boot.SpringApplication(com.TreDL.ecommerce.EcommerceApplication.class);
        app.setEnvironment(e);app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);app.setLogStartupInfo(false);
        app.addListeners((org.springframework.context.ApplicationListener<org.springframework.boot.context.event.ApplicationPreparedEvent>)event->prepared.set(true));
        assertThatThrownBy(()->app.run("--spring.profiles.active=checkout-release")).isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining("not-base64-sensitive-marker").hasMessageNotContaining("c2hvcnQ=");
        assertThat(prepared).isFalse();
    }
    @Test void releaseCannotBeActivatedAsImplicitDefaultProfile() throws Exception {
        var e=environment(values());e.setActiveProfiles();e.setDefaultProfiles("checkout-release");
        assertThatThrownBy(()->new ReleaseEnvironment().postProcessEnvironment(e,null)).hasMessage("Release profile must be used alone");
        e.setDefaultProfiles("dev","checkout-release");
        assertThatThrownBy(()->new ReleaseEnvironment().postProcessEnvironment(e,null)).hasMessage("Release profile must be used alone");
    }
    @ParameterizedTest @ValueSource(strings={"jakarta", "javax"})
    void jpaSchemaGenerationAliasesCannotOverrideValidate(String namespace) throws Exception {
        var e=environment(values());e.setProperty("spring.jpa.properties."+namespace+".persistence.schema-generation.database.action","drop-and-create");
        assertThatThrownBy(()->new ReleaseEnvironment().postProcessEnvironment(e,null)).hasMessage("JPA schema generation override forbidden");
    }
    @Test void otherProfilesRemainUnchanged() {var e=new MockEnvironment();e.setActiveProfiles("test");new ReleaseEnvironment().postProcessEnvironment(e,null);assertThat(e.containsProperty("spring.datasource.url")).isFalse();}
}
