package com.TreDL.ecommerce.controller;

import org.springframework.stereotype.Controller;
import com.TreDL.ecommerce.service.OrderHistoryService;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import java.security.Principal;

@Controller
public class OrdersController {
    private final OrderHistoryService history;

    public OrdersController(OrderHistoryService history) {
        this.history = history;
    }

    @GetMapping("/user")
    public String getUser(Principal principal, Model model) {
        model.addAttribute("orders", history.forCustomer(principal.getName()));
        return "user";
    }
}
