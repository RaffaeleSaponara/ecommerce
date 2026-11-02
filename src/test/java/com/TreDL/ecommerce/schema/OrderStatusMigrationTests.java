package com.TreDL.ecommerce.schema;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class OrderStatusMigrationTests {
    @Test void v5PreservesHistoricalOrdersAndMarksThemAlreadyDeductedWithoutInventingHistory() throws Exception {
        String url="jdbc:h2:mem:status_"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/checkout-v2").target("4").cleanDisabled(true).load().migrate();
        try(Connection db=DriverManager.getConnection(url,"sa","");Statement s=db.createStatement()) {
            s.executeUpdate("INSERT INTO customers(id,username,password,email,role) VALUES(1,'old','test','old@test','USER')");
            s.executeUpdate("INSERT INTO orders(id,id_customer,date,total,currency,ship_status,idempotency_key,request_hash) VALUES(1,1,CURRENT_TIMESTAMP,12.50,'EUR','In elaborazione',X'01',REPEAT('a',32))");
            var upgrade=Flyway.configure().dataSource(url,"sa","").locations("classpath:db/checkout-v2").target("5").cleanDisabled(true).load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            try(ResultSet row=s.executeQuery("SELECT ship_status,total,stock_deducted FROM orders WHERE id=1")) {
                assertThat(row.next()).isTrue(); assertThat(row.getString(1)).isEqualTo("In elaborazione");
                assertThat(row.getBigDecimal(2)).isEqualByComparingTo("12.50");assertThat(row.getBoolean(3)).isTrue();
            }
            try(ResultSet row=s.executeQuery("SELECT COUNT(*) FROM order_status_changes")) {row.next();assertThat(row.getInt(1)).isZero();}
            upgrade.validate();assertThat(upgrade.migrate().migrationsExecuted).isZero();
        }
    }
}
