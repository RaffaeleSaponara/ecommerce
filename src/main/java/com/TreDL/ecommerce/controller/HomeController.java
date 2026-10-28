package com.TreDL.ecommerce.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class HomeController {

    @ModelAttribute
    public void initializeCsrfToken(CsrfToken csrfToken) {
        // Create the token's session before Thymeleaf can flush a large page to the client.
        csrfToken.getToken();
    }

    @GetMapping("/")
    public String home() {
        return "home1";
    }
    @GetMapping("/home")
    public String getHome(@AuthenticationPrincipal UserDetails userDetails, Model model) {
        return "home";
    }

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @GetMapping("/registrazione")
    public String registrazionePage() {
        return "registrazione";
    }

    @GetMapping("/prodotti")
    public String getProdotti() {
        return "prodotti";
    }

    @GetMapping("/cart")
    public String getCart() {
        return "cart";
    }





}
