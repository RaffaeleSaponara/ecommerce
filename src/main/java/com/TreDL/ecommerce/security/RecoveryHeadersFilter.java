package com.TreDL.ecommerce.security;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import java.io.IOException;

public final class RecoveryHeadersFilter extends OncePerRequestFilter {
    private final AntPathRequestMatcher forgot=new AntPathRequestMatcher("/forgot-password");
    private final AntPathRequestMatcher reset=new AntPathRequestMatcher("/reset-password");
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if(forgot.matches(request) || reset.matches(request)) {
            response.setHeader("Referrer-Policy","no-referrer");
            response.setHeader("Cache-Control","no-store, max-age=0");
            response.setHeader("X-Robots-Tag","noindex, nofollow");
            response.setHeader("Content-Security-Policy","default-src 'none'; style-src 'self'; script-src 'self'; img-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");
        }
        chain.doFilter(request,response);
    }
}
