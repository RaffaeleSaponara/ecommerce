package com.TreDL.ecommerce.schema;

import java.util.UUID;

class CheckoutV2H2SchemaTests extends CheckoutV2SchemaContract {
    @Override protected String url() {
        return "jdbc:h2:mem:checkout_v2_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
    }
    @Override protected String username() { return "sa"; }
    @Override protected String password() { return ""; }
}
