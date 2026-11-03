package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PaymentStatesTests {
    @Test void paymentTerminalStatesCannotBeOverwrittenOrReopened() {
        for(var status:PaymentStatus.values())for(var next:PaymentStatus.values()) {
            if(status.terminal())assertThat(status.allows(next)).isEqualTo(status==next);
        }
        assertThat(PaymentStatus.CREATED.allows(PaymentStatus.PENDING)).isTrue();
        assertThat(PaymentStatus.PENDING.allows(PaymentStatus.CREATED)).isFalse();
    }
    @Test void orderAndRefundStatesStaySeparate() {
        assertThat(OrderStatus.IN_ATTESA_PAGAMENTO.allows(OrderStatus.CONFERMATO)).isTrue();
        assertThat(OrderStatus.IN_ATTESA_PAGAMENTO.allows(OrderStatus.ANNULLATO)).isTrue();
        assertThat(OrderStatus.IN_ATTESA_PAGAMENTO.allows(OrderStatus.IN_PREPARAZIONE)).isFalse();
        assertThat(OrderStatus.CONSEGNATO.allows(OrderStatus.ANNULLATO)).isFalse();
        assertThat(RefundStatus.REQUESTED.allows(RefundStatus.PENDING)).isTrue();
        assertThat(RefundStatus.PENDING.allows(RefundStatus.SUCCEEDED)).isTrue();
        assertThat(RefundStatus.SUCCEEDED.allows(RefundStatus.PENDING)).isFalse();
    }
}
