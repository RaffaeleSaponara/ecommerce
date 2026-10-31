package com.TreDL.ecommerce.schema;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ProductImagesMigrationTests {
    @Test void upgradeV1ToV2PreservesExistingProductAndLegacyImage() throws Exception {
        String url="jdbc:h2:mem:images_"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/checkout-v2").target("1").cleanDisabled(true).load().migrate();
        try(Connection db=DriverManager.getConnection(url,"sa","");Statement s=db.createStatement()) {
            s.executeUpdate("INSERT INTO products(id,nome,prezzo,image) VALUES (1,'Legacy',12.50,'/images/b1.jpg')");
            Flyway upgraded=Flyway.configure().dataSource(url,"sa","").locations("classpath:db/checkout-v2").target("2").cleanDisabled(true).load();
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgraded.info().current().getVersion().toString()).isEqualTo("2");
            try(ResultSet r=s.executeQuery("SELECT nome,prezzo,image FROM products WHERE id=1")) {
                assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("Legacy");
                assertThat(r.getBigDecimal(2)).isEqualByComparingTo("12.50");assertThat(r.getString(3)).isEqualTo("/images/b1.jpg");
            }
            upgraded.validate();assertThat(upgraded.migrate().migrationsExecuted).isZero();
        }
    }
}
