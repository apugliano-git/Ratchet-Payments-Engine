package com.ratchet.reservation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/health").permitAll()
                // SEGURIDAD - DEUDA TÉCNICA v1: este endpoint no tiene autenticación de servicio-a-servicio. 
                // Solo protegido por no estar expuesto fuera de la red interna de Docker. Antes de cualquier 
                // despliegue real, agregar autenticación mutua (mTLS) o un token de servicio compartido entre 
                // payment-service y reservation-service.
                .requestMatchers("/internal/**").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));
            
        // CSRF should be disabled for stateless APIs
        http.csrf(csrf -> csrf.disable());
        
        return http.build();
    }
}
