package com.TreDL.ecommerce.release;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.*;
import java.util.*;

/** Runs after ConfigData and before creation of any datasource or JPA bean. */
public final class ReleaseEnvironment implements EnvironmentPostProcessor, Ordered {
    public static final Map<String,String> INVARIANTS=Map.ofEntries(
        Map.entry("server.address","127.0.0.1"), Map.entry("spring.jpa.hibernate.ddl-auto","validate"), Map.entry("spring.jpa.properties.hibernate.hbm2ddl.auto","validate"),
        Map.entry("spring.jpa.generate-ddl","false"), Map.entry("spring.jpa.open-in-view","false"), Map.entry("spring.jpa.show-sql","false"),
        Map.entry("spring.flyway.enabled","false"), Map.entry("app.data-initializer.enabled","false"),
        Map.entry("spring.h2.console.enabled","false"), Map.entry("spring.sql.init.mode","never"),
        Map.entry("server.servlet.session.cookie.http-only","true"), Map.entry("server.servlet.session.cookie.same-site","lax"),
        Map.entry("server.servlet.session.cookie.secure","true"));
    @Override public int getOrder(){return Ordered.LOWEST_PRECEDENCE-10;}
    @Override public void postProcessEnvironment(ConfigurableEnvironment env,SpringApplication application) {
        boolean explicit=Arrays.asList(env.getActiveProfiles()).contains(ReleaseDatabase.PROFILE);
        boolean implicit=env.getActiveProfiles().length==0 && Arrays.asList(env.getDefaultProfiles()).contains(ReleaseDatabase.PROFILE);
        if(!explicit && !implicit) return;
        ReleaseDatabase.require(Arrays.equals(env.getActiveProfiles(),new String[]{ReleaseDatabase.PROFILE}),"Release profile must be used alone");
        ReleaseDatabase.Target target=ReleaseDatabase.target(env::getProperty);
        String hmac=ReleaseDatabase.required(env::getProperty,"CHECKOUT_QUOTE_SECRET_BASE64"); ReleaseDatabase.hmac(hmac);
        ReleaseDatabase.require(env.getProperty("CHECKOUT_V2_MIGRATION_USERNAME")==null && env.getProperty("CHECKOUT_V2_MIGRATION_PASSWORD")==null,"Migration credentials must not be supplied to the application");
        for(String alias:List.of("spring.jpa.properties.jakarta.persistence.schema-generation.database.action",
                "spring.jpa.properties.javax.persistence.schema-generation.database.action"))
            ReleaseDatabase.require(env.getProperty(alias)==null,"JPA schema generation override forbidden");
        for(var entry:INVARIANTS.entrySet()) ReleaseDatabase.require(entry.getValue().equals(env.getProperty(entry.getKey())),"Unsafe release setting: "+entry.getKey());
        ReleaseDatabase.require(target.url.equals(env.getProperty("spring.datasource.url")),"Runtime datasource override forbidden");
        ReleaseDatabase.require(target.username.equals(env.getProperty("spring.datasource.username")),"Runtime username override forbidden");
        Map<String,Object> pinned=new HashMap<>(INVARIANTS);
        pinned.put("spring.datasource.url",target.url); pinned.put("checkout.quote-secret-base64",hmac);
        env.getPropertySources().addFirst(new MapPropertySource("verified-checkout-release",pinned));
    }
}
