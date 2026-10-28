package com.TreDL.ecommerce.controller;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminPageController {
    @GetMapping("/admin")
    public String admin(CsrfToken csrfToken) {
        csrfToken.getToken();
        return "admin";
    }
}
