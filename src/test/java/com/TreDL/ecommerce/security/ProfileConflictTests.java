package com.TreDL.ecommerce.security;

import com.TreDL.ecommerce.controller.ProfileController;
import com.TreDL.ecommerce.service.ProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfileConflictTests {
    @Test void knownUsernameConstraintHasFriendlyMessageButOtherDatabaseErrorsDoNotBecomeDuplicates() {
        ProfileController controller=new ProfileController(mock(ProfileService.class));
        assertThat(controller.database(error(1062,"23000","customers.uq_customers_username")))
                .isEqualTo("redirect:/user?profileError=username#account-settings");
        assertThat(controller.database(error(23505,"23505","uq_customers_username_INDEX")))
                .isEqualTo("redirect:/user?profileError=username#account-settings");
        assertThat(controller.database(error(1048,"23000","password cannot be null")))
                .isEqualTo("redirect:/user?profileError=storage#account-settings");
        assertThat(controller.database(error(1062,"23000","unknown_constraint")))
                .isEqualTo("redirect:/user?profileError=storage#account-settings");
    }
    DataIntegrityViolationException error(int code,String state,String detail) {
        return new DataIntegrityViolationException("Internal SQL",new SQLException(detail,state,code));
    }
}
