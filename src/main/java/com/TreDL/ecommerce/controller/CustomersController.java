package com.TreDL.ecommerce.controller;

import com.TreDL.ecommerce.service.RegistrationService;
import com.TreDL.ecommerce.service.EmailVerificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import java.sql.SQLException;

@Controller
public class CustomersController {
    private final RegistrationService registration;
    private final EmailVerificationService verification;
    public CustomersController(RegistrationService registration,EmailVerificationService verification) { this.registration=registration;this.verification=verification; }

    @PostMapping("/signup")
    public String signup(@RequestParam(required=false) String username,
                         @RequestParam(required=false) String email,
                         @RequestParam(required=false) String password,
                         @RequestParam(required=false) String confirmPassword,HttpServletRequest request) {
        try {
            registration.register(username,email,password,confirmPassword);
            try{verification.request(email,request.getRemoteAddr());}catch(org.springframework.dao.DataAccessException error){/* Registration already committed; resend remains available. */}
            return "redirect:/login?registered";
        } catch(RegistrationService.InvalidRegistration error) {
            if(error.code().equals("email"))return "redirect:/login?registered";
            return "redirect:/registrazione?error="+error.code();
        } catch(DataIntegrityViolationException error) {
            // Only a known unique constraint is a registration conflict. Never expose SQL/details.
            for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
                if(cause instanceof SQLException sql && (sql.getErrorCode()==1062 || "23505".equals(sql.getSQLState()))) {
                    String detail=sql.getMessage()==null?"":sql.getMessage().toLowerCase(java.util.Locale.ROOT);
                    if(detail.contains("uq_customers_email_normalized")) return "redirect:/login?registered";
                    if(detail.contains("uq_customers_username")) return "redirect:/registrazione?error=username";
                }
            }
            return "redirect:/registrazione?error=storage";
        } catch(org.springframework.dao.DataAccessException error) {
            return "redirect:/registrazione?error=storage";
        }
    }
}
