package com.TreDL.ecommerce.controller;

import com.TreDL.ecommerce.service.*;
import com.TreDL.ecommerce.security.RecoveryRateLimiter;
import jakarta.servlet.http.*;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class PasswordRecoveryController {
    private final PasswordRecoveryService recovery;
    private final PasswordResetStore store;
    private final RecoveryRateLimiter limits;
    public PasswordRecoveryController(PasswordRecoveryService recovery,PasswordResetStore store,RecoveryRateLimiter limits) {
        this.recovery=recovery;this.store=store;this.limits=limits;
    }
    @ModelAttribute public void csrf(CsrfToken token){token.getToken();}
    @GetMapping("/forgot-password") public String requestForm(){return "forgot-password";}
    @PostMapping("/forgot-password")
    public String request(@RequestParam(required=false) String email,HttpServletRequest request) {
        try {recovery.request(email,request.getRemoteAddr());}
        catch(DataAccessException error) { /* Same response, including database outages. Never log request details. */ }
        return "redirect:/forgot-password?sent";
    }
    @GetMapping("/reset-password")
    public String resetForm(@RequestParam(required=false) String token,Model model) {
        boolean valid=store.valid(token);
        model.addAttribute("invalidToken",!valid);
        if(valid)model.addAttribute("token",token);
        return "reset-password";
    }
    @PostMapping("/reset-password")
    public String reset(@RequestParam(required=false) String token,@RequestParam(required=false) String password,
                        @RequestParam(required=false) String confirmPassword,HttpServletRequest request,HttpServletResponse response,Model model) {
        model.addAttribute("token",token);
        model.addAttribute("invalidToken",false);
        if(!limits.resetIp(request.getRemoteAddr())) {
            model.addAttribute("error","Troppi tentativi. Attendi e riprova.");return "reset-password";
        }
        try {
            recovery.reset(token,password,confirmPassword);
            new SecurityContextLogoutHandler().logout(request,response,SecurityContextHolder.getContext().getAuthentication());
            return "redirect:/login?passwordReset";
        } catch(PasswordResetStore.InvalidToken error) {
            model.addAttribute("invalidToken",true);model.asMap().remove("token");return "reset-password";
        } catch(RegistrationService.InvalidRegistration error) {
            model.addAttribute("error",error.code().equals("password")?"La conferma della password non corrisponde.":"Password non valida: almeno 12 caratteri, una lettera e un numero, massimo 72 byte UTF-8, senza caratteri di controllo.");
            return "reset-password";
        } catch(DataAccessException error) {
            model.addAttribute("error","Operazione temporaneamente non disponibile. Riprova più tardi.");return "reset-password";
        }
    }
}
