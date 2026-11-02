package com.TreDL.ecommerce.security;
import com.TreDL.ecommerce.controller.CustomersController;
import com.TreDL.ecommerce.service.RegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import java.sql.SQLException;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class RegistrationConflictTests {
    @Test void onlyKnownDuplicateConstraintsProduceConflictMessages() {
        assertResult(1062,"23000","Duplicate entry for key customers.uq_customers_email_normalized","email");
        assertResult(1062,"23000","Duplicate entry for key customers.uq_customers_username","username");
        assertResult(1048,"23000","column password cannot be null","storage");
        assertResult(1062,"23000","unknown_unique_constraint","storage");
    }
    void assertResult(int vendor,String state,String detail,String code) {
        var service=mock(RegistrationService.class);
        doThrow(new DataIntegrityViolationException("Internal database detail",new SQLException(detail,state,vendor)))
                .when(service).register(any(),any(),any(),any());
        assertThat(new CustomersController(service,mock(com.TreDL.ecommerce.service.EmailVerificationService.class)).signup("Name","a@example.com","good-password1","good-password1",new org.springframework.mock.web.MockHttpServletRequest()))
                .isEqualTo(code.equals("email")?"redirect:/login?registered":"redirect:/registrazione?error="+code);
    }
}
