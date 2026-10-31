package com.TreDL.ecommerce.service;
@org.springframework.test.context.ContextConfiguration(initializers=MySqlCheckoutTestDatabase.Initializer.class)
class StockCheckoutMySqlTests extends StockCheckoutContract {
    @org.junit.jupiter.api.Test void databaseRejectsNegativeStock() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> stock(-1))
            .isInstanceOfSatisfying(org.springframework.jdbc.UncategorizedSQLException.class, error -> {
                org.assertj.core.api.Assertions.assertThat(error.getSQLException().getErrorCode()).isEqualTo(3819);
                org.assertj.core.api.Assertions.assertThat(error.getMessage()).contains("ck_products_stock");
            });
        org.assertj.core.api.Assertions.assertThat(available()).isEqualTo(5);
    }
}
