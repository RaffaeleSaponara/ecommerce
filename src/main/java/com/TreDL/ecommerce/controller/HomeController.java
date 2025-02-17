package com.TreDL.ecommerce.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "home";
    }
    @GetMapping("/home")
    public String getHome(@AuthenticationPrincipal UserDetails userDetails, Model model) {
        return "home";
    }

    @GetMapping("/login")
    public String loginPage() {
        return "login";
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
