package com.TreDL.ecommerce.controller;
import com.TreDL.ecommerce.service.*;
import com.TreDL.ecommerce.security.VerificationRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class EmailVerificationController {
    private final EmailVerificationService service;
    private final EmailVerificationStore store;
    private final VerificationRateLimiter limits;
    public EmailVerificationController(EmailVerificationService service,EmailVerificationStore store,VerificationRateLimiter limits){this.service=service;this.store=store;this.limits=limits;}
    @ModelAttribute public void csrf(CsrfToken token){token.getToken();}
    @GetMapping("/resend-verification") public String resend(){return "resend-verification";}
    @PostMapping("/resend-verification")
    public String request(@RequestParam(required=false) String email,HttpServletRequest request) {
        try{service.request(email,request.getRemoteAddr());}catch(DataAccessException error){/* Generic response, no sensitive details. */}
        return "redirect:/resend-verification?sent";
    }
    @GetMapping("/verify-email")
    public String form(@RequestParam(required=false) String token,Model model){model.addAttribute("token",token);return "verify-email";}
    @PostMapping("/verify-email")
    public String confirm(@RequestParam(required=false) String token,HttpServletRequest request,Model model) {
        if(!limits.confirmationIp(request.getRemoteAddr())) {model.addAttribute("message","Troppi tentativi. Attendi e riprova.");return "verify-email";}
        try{store.confirm(token);return "redirect:/login?emailVerified";}
        catch(EmailVerificationStore.LinkException error){model.addAttribute("message",error.expired()?"Link scaduto. Richiedi una nuova email di verifica.":"Link non valido o già utilizzato. Richiedi una nuova email di verifica.");return "verify-email";}
        catch(DataAccessException error){model.addAttribute("message","Operazione temporaneamente non disponibile. Richiedi un nuovo link o riprova più tardi.");return "verify-email";}
    }
}
