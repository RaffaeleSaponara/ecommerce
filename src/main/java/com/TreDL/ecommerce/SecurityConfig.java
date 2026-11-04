package com.TreDL.ecommerce;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,com.TreDL.ecommerce.security.AuthAttemptLimiter limits,org.springframework.security.core.session.SessionRegistry sessions) throws Exception {
        var success=new org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler();
        success.setDefaultTargetUrl("/");success.setAlwaysUseDefaultTargetUrl(true);
        var failure=new org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler("/login?error");
        http
                .csrf(csrf->csrf.ignoringRequestMatchers(new AntPathRequestMatcher("/api/stripe/webhook","POST")))
                .addFilterBefore(new com.TreDL.ecommerce.security.RecoveryHeadersFilter(),org.springframework.security.web.csrf.CsrfFilter.class)
                .sessionManagement(session->session.maximumSessions(-1).sessionRegistry(sessions).expiredUrl("/login?sessionExpired"))
                .addFilterBefore(new com.TreDL.ecommerce.security.AuthAttemptFilter(limits),org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(org.springframework.http.HttpMethod.GET,"/privacy","/cookies","/termini","/spedizioni-resi").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/stripe/webhook").permitAll()
                        .requestMatchers("/forgot-password","/reset-password").permitAll()
                        .requestMatchers("/verify-email","/resend-verification").permitAll()
                        .requestMatchers("/admin", "/admin/**", "/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/", "/home", "/registrazione", "/signup", "/prodotti", "/prodotti/**", "/api/products/*", "/media/products/*", "/css/**", "/js/**", "/images/**", "/videos/**", "/auth/check").permitAll() // Catalogo pubblico e risorse statiche
                        .anyRequest().authenticated() // Tutti gli altri URL richiedono autenticazione
                )
                .formLogin(login -> login
                        .loginPage("/login")
                        .defaultSuccessUrl("/", true)
                        .successHandler((request,response,authentication)->{
                            var ticket=(com.TreDL.ecommerce.security.AuthAttemptLimiter.Ticket)request.getAttribute(com.TreDL.ecommerce.security.AuthAttemptFilter.TICKET);
                            if(limits.complete(ticket,true)) success.onAuthenticationSuccess(request,response,authentication);
                            else {
                                new org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler().logout(request,response,authentication);
                                response.sendRedirect(request.getContextPath()+"/login?limited");
                            }
                        })
                        .failureHandler((request,response,error)->{
                            var ticket=(com.TreDL.ecommerce.security.AuthAttemptLimiter.Ticket)request.getAttribute(com.TreDL.ecommerce.security.AuthAttemptFilter.TICKET);
                            // DAO authentication wraps failures from user lookup. A blocked canonical
                            // identity is rejected without counting it again or extending the block.
                            for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
                                if(cause instanceof com.TreDL.ecommerce.security.AuthAttemptLimiter.Limited limited) {
                                    limits.cancel(ticket);
                                    response.setHeader("Retry-After",Long.toString(limited.retryAfter()));
                                    response.sendRedirect(request.getContextPath()+"/login?limited");return;
                                }
                            }
                            if(limits.complete(ticket,false)) failure.onAuthenticationFailure(request,response,error);
                            else response.sendRedirect(request.getContextPath()+"/login?limited");
                        })
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/")
                        .permitAll()
                )
                .exceptionHandling(errors -> errors.defaultAuthenticationEntryPointFor(
                        new org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint("/login"),
                        new AntPathRequestMatcher("/recesso/**"))
                        .defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        new AntPathRequestMatcher("/cart/**"))
                        .defaultAuthenticationEntryPointFor((request, response, error) -> {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"message\":\"Autenticazione richiesta\"}");
                        }, new org.springframework.security.web.util.matcher.OrRequestMatcher(new AntPathRequestMatcher("/api/admin/**"), new AntPathRequestMatcher("/api/addresses/**")))
                        .defaultAuthenticationEntryPointFor(
                                new org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint("/login"),
                                new AntPathRequestMatcher("/admin"))
                        .accessDeniedHandler((request, response, error) -> {
                            if (request.getServletPath().startsWith("/api/admin/") || request.getServletPath().startsWith("/api/addresses")) {
                                response.setStatus(403);
                                response.setContentType("application/json;charset=UTF-8");
                                response.getWriter().write("{\"message\":\"Operazione non consentita\"}");
                            } else {
                                new org.springframework.security.web.access.AccessDeniedHandlerImpl().handle(request, response, error);
                            }
                        }));

        return http.build();
    }

    @Bean
    public org.springframework.security.core.session.SessionRegistry sessionRegistry() {
        return new org.springframework.security.core.session.SessionRegistryImpl();
    }
    @Bean
    public org.springframework.security.web.session.HttpSessionEventPublisher sessionEvents() {
        return new org.springframework.security.web.session.HttpSessionEventPublisher();
    }
    @Bean
    public com.TreDL.ecommerce.security.AuthAttemptLimiter authenticationLimits() {
        return new com.TreDL.ecommerce.security.AuthAttemptLimiter(java.time.Clock.systemUTC());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }
}
