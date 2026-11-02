package com.TreDL.ecommerce.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Created only inside the security chain, after CSRF and before form authentication. */
public final class AuthAttemptFilter extends OncePerRequestFilter {
    public static final String TICKET=AuthAttemptFilter.class.getName()+".ticket";
    private record Attempt(AuthAttemptLimiter limiter,AuthAttemptLimiter.Ticket ticket) {}
    private static final ThreadLocal<Attempt> CURRENT=new ThreadLocal<>();
    public static void bindAccount(String canonicalEmail) {
        Attempt attempt=CURRENT.get();
        if(attempt!=null) attempt.limiter().bindAccount(attempt.ticket(),canonicalEmail);
    }
    private final AuthAttemptLimiter limiter;
    private final AntPathRequestMatcher login=new AntPathRequestMatcher("/login","POST");
    private final AntPathRequestMatcher signup=new AntPathRequestMatcher("/signup","POST");
    public AuthAttemptFilter(AuthAttemptLimiter limiter) {this.limiter=limiter;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        AuthAttemptLimiter.Ticket ticket=null;
        try {
            try {
                if(login.matches(request)) {
                    ticket=limiter.beginLogin(request.getParameter("username"),request.getRemoteAddr());
                    request.setAttribute(TICKET,ticket);
                    CURRENT.set(new Attempt(limiter,ticket));
                } else if(signup.matches(request)) limiter.signup(request.getRemoteAddr());
            } catch(AuthAttemptLimiter.Limited limited) {
                response.setHeader("Retry-After",Long.toString(limited.retryAfter()));
                response.sendRedirect(request.getContextPath()+(signup.matches(request)?"/registrazione?error=limited":"/login?limited"));
                return;
            }
            chain.doFilter(request,response);
        } finally {CURRENT.remove();limiter.cancel(ticket);}
    }
}
