package com.TreDL.ecommerce.controller;

import org.springframework.stereotype.Controller;
import com.TreDL.ecommerce.service.OrderHistoryService;
import com.TreDL.ecommerce.service.ProfileService;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import java.security.Principal;

@Controller
public class OrdersController {
    private final OrderHistoryService history;
    private final ProfileService profiles;

    public OrdersController(OrderHistoryService history,ProfileService profiles) {
        this.history = history;
        this.profiles=profiles;
    }

    @GetMapping("/user")
    public String getUser(Principal principal, Model model) {
        model.addAttribute("orders", history.forCustomer(principal.getName()));
        model.addAttribute("profile", profiles.view(principal.getName()));
        return "user";
    }
}
