package com.TreDL.ecommerce.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Informational drafts only: no change to checkout or contract acceptance. */
@Controller
public class LegalController {
    @GetMapping("/privacy") public String privacy(Model model) { return page(model, "privacy", "Privacy Policy"); }
    @GetMapping("/cookies") public String cookies(Model model) { return page(model, "cookies", "Cookie Policy"); }
    @GetMapping("/termini") public String terms(Model model) { return page(model, "terms", "Termini e condizioni"); }
    @GetMapping("/spedizioni-resi") public String shipping(Model model) { return page(model, "shipping", "Spedizioni e resi"); }
    private String page(Model model, String section, String title) {
        model.addAttribute("section", section);
        model.addAttribute("title", title);
        return "legal";
    }
}
