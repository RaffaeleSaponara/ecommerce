package com.TreDL.ecommerce.controller;

import com.TreDL.ecommerce.service.ProfileService;
import com.TreDL.ecommerce.service.RegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.sql.SQLException;

@Controller
public class ProfileController {
    private final ProfileService profiles;
    public ProfileController(ProfileService profiles) {this.profiles=profiles;}
    @PostMapping("/user/profile")
    public String username(Principal principal,@RequestParam(required=false) String username) {
        profiles.updateUsername(principal.getName(),username);
        return "redirect:/user?profileSaved#my-profile";
    }
    @PostMapping("/user/password")
    public String password(Authentication authentication,HttpServletRequest request,HttpServletResponse response,
            @RequestParam(required=false) String currentPassword,@RequestParam(required=false) String newPassword,
            @RequestParam(required=false) String confirmPassword) {
        // The proxied service commits before logout. Failed updates keep the current session.
        profiles.changePassword(authentication.getName(),currentPassword,newPassword,confirmPassword);
        new SecurityContextLogoutHandler().logout(request,response,authentication);
        return "redirect:/login?passwordChanged";
    }
    @ExceptionHandler(RegistrationService.InvalidRegistration.class)
    public String invalid(RegistrationService.InvalidRegistration error) {
        return "redirect:/user?profileError="+error.code()+"#account-settings";
    }
    @ExceptionHandler(DataAccessException.class)
    public String database(DataAccessException error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if(cause instanceof SQLException sql && (sql.getErrorCode()==1062 || "23505".equals(sql.getSQLState()))
                    && sql.getMessage()!=null && sql.getMessage().toLowerCase(java.util.Locale.ROOT).contains("uq_customers_username"))
                return "redirect:/user?profileError=username#account-settings";
        }
        return "redirect:/user?profileError=storage#account-settings";
    }
}
