package com.TreDL.ecommerce.controller;

import com.TreDL.ecommerce.service.RegistrationService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.sql.SQLException;

@Controller
public class CustomersController {
    private final RegistrationService registration;
    public CustomersController(RegistrationService registration) { this.registration=registration; }

    @PostMapping("/signup")
    public String signup(@RequestParam(required=false) String username,
                         @RequestParam(required=false) String email,
                         @RequestParam(required=false) String password,
                         @RequestParam(required=false) String confirmPassword) {
        try {
            registration.register(username,email,password,confirmPassword);
            return "redirect:/login";
        } catch(RegistrationService.InvalidRegistration error) {
            return "redirect:/registrazione?error="+error.code();
        } catch(DataIntegrityViolationException error) {
            // Only a known unique constraint is a registration conflict. Never expose SQL/details.
            for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
                if(cause instanceof SQLException sql && (sql.getErrorCode()==1062 || "23505".equals(sql.getSQLState()))) {
                    String detail=sql.getMessage()==null?"":sql.getMessage().toLowerCase(java.util.Locale.ROOT);
                    if(detail.contains("uq_customers_email_normalized")) return "redirect:/registrazione?error=email";
                    if(detail.contains("uq_customers_username")) return "redirect:/registrazione?error=username";
                }
            }
            return "redirect:/registrazione?error=storage";
        } catch(org.springframework.dao.DataAccessException error) {
            return "redirect:/registrazione?error=storage";
        }
    }
}
