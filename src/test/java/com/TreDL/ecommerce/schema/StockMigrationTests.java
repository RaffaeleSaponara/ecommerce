package com.TreDL.ecommerce.schema;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class StockMigrationTests {
    @Test void v4PreservesProductsAndStartsUnknownInventoryAtZero() throws Exception {
        String url="jdbc:h2:mem:stock_"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/checkout-v2").target("3").cleanDisabled(true).load().migrate();
        try(Connection db=DriverManager.getConnection(url,"sa","");Statement statement=db.createStatement()) {
            statement.executeUpdate("INSERT INTO products(id,nome,prezzo) VALUES(1,'Esistente',12.50)");
            Flyway upgraded=Flyway.configure().dataSource(url,"sa","").locations("classpath:db/checkout-v2").cleanDisabled(true).load();
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
            try(ResultSet row=statement.executeQuery("SELECT nome,prezzo,stock FROM products WHERE id=1")) {
                assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("Esistente");
                assertThat(row.getBigDecimal(2)).isEqualByComparingTo("12.50");assertThat(row.getInt(3)).isZero();
            }
            assertThatThrownBy(()->statement.executeUpdate("UPDATE products SET stock=-1 WHERE id=1")).isInstanceOf(SQLException.class);
            upgraded.validate();assertThat(upgraded.migrate().migrationsExecuted).isZero();
        }
    }
}
