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
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin", "/admin/**", "/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/", "/home", "/registrazione", "/signup", "/prodotti", "/prodotti/**", "/api/products/*", "/media/products/*", "/css/**", "/js/**", "/images/**", "/videos/**", "/auth/check").permitAll() // Catalogo pubblico e risorse statiche
                        .anyRequest().authenticated() // Tutti gli altri URL richiedono autenticazione
                )
                .formLogin(login -> login
                        .loginPage("/login")
                        .defaultSuccessUrl("/", true)
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/")
                        .permitAll()
                )
                .exceptionHandling(errors -> errors.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        new AntPathRequestMatcher("/cart/**"))
                        .defaultAuthenticationEntryPointFor((request, response, error) -> {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"message\":\"Autenticazione richiesta\"}");
                        }, new AntPathRequestMatcher("/api/admin/**"))
                        .defaultAuthenticationEntryPointFor(
                                new org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint("/login"),
                                new AntPathRequestMatcher("/admin"))
                        .accessDeniedHandler((request, response, error) -> {
                            if (request.getServletPath().startsWith("/api/admin/")) {
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
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }
}
