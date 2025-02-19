package com.TreDL.ecommerce.controller;

import com.TreDL.ecommerce.model.Customers;
import com.TreDL.ecommerce.service.CustomersService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;


@Controller
public class CustomersController {

    @Autowired
    private CustomersService customersService;


    @PostMapping("/signup")
    public String signup(@RequestParam("username") String username,
                       @RequestParam("email") String email,
                       @RequestParam("password") String password,
                       @RequestParam("confirmPassword") String confirmPassword) {

        Customers c = customersService.getByEmail(email);
        if(customersService.getByEmail(email).getEmail() == null){
            if (password.equals(confirmPassword)) {
                Customers customer = new Customers();
                customer.setEmail(email);
                customer.setPassword(password);
                customer.setUsername(username);
                customer.setRole("USER");
                customersService.addCustomer(customer);
                return "redirect:/login";  // oppure "redirect:/home" se la registrazione è riuscita
            } else {
                // Se le password non corrispondono, mostra errore
                return "redirect:/registrazione?error=true";
            }
        }else{
            return "redirect:/registrazione?error=true";
        }
    }

}
