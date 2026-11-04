package com.TreDL.ecommerce.service;

import com.TreDL.ecommerce.model.Orders;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

/** Capture the actual draft pages at purchase, including Stripe reservations before payment. */
@Component
public class ContractTerms {
    public static final String VERSION="DRAFT-TEST-2026-11-04-v1";
    private final TemplateEngine templates;
    public ContractTerms(TemplateEngine templates){this.templates=templates;}
    public void capture(Orders order){
        StringBuilder text=new StringBuilder("CONDIZIONI DI PROVA — NON DEFINITIVE\nVersione: "+VERSION+"\n");
        for(String section:new String[]{"terms","shipping"}){
            Context context=new Context(Locale.ITALIAN);
            context.setVariable("section",section);context.setVariable("title",section.equals("terms")?"Termini e condizioni":"Spedizioni e resi");
            String html=templates.process("legal",context);
            text.append(org.springframework.web.util.HtmlUtils.htmlUnescape(html.replaceAll("(?s)<head>.*?</head>","")
                .replaceAll("<[^>]+>","\n").replaceAll("[\\t ]+"," ").replaceAll("\\n\\s*\\n+","\n"))).append("\n");
        }
        order.captureContract(VERSION,LocalDateTime.now(ZoneOffset.UTC),text.toString());
    }
}
