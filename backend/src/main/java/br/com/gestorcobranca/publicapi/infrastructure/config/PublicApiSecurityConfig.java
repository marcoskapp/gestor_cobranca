package br.com.gestorcobranca.publicapi.infrastructure.config;

import br.com.gestorcobranca.publicapi.application.port.in.ApiVersionUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.gateway.IntegrationAuthentication;
import br.com.gestorcobranca.publicapi.application.port.in.gateway.PublicApiMetering;
import br.com.gestorcobranca.publicapi.web.security.PublicApiRequestFilter;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.web.ApiPaths;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import java.time.Clock;

/**
 * Cadeia de segurança própria da API pública ({@code /public-api/**}), separada da cadeia dos usuários (identity):
 * não aceita o JWT de usuário nem o token do portal; só o token de integração, verificado pelo
 * {@link PublicApiRequestFilter}. Sem sessão, sem cookies, sem CORS (integração servidor a servidor). Rotas de
 * documentação, contrato, versões e changelog são públicas; todo o resto exige o ator {@code INTEGRATION}, e cada
 * endpoint exige ainda o escopo ({@code @PreAuthorize("hasAuthority('SCOPE_…')")}).
 */
@Configuration
class PublicApiSecurityConfig {

    static final String INTEGRATION_AUTHORITY = "ACTOR_" + Actor.ActorType.INTEGRATION.name();

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 50)
    SecurityFilterChain publicApiSecurity(HttpSecurity http, IntegrationAuthentication authentication,
                                          PublicApiMetering metering, ApiVersionUseCases versions, JsonCodec json,
                                          Clock clock) throws Exception {
        String root = ApiPaths.PUBLIC_API;
        http.securityMatcher(root + "/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(c -> c.requireExplicitSave(true))
                .requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .headers(h -> h
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        .frameOptions(f -> f.deny())
                        .contentTypeOptions(c -> { })
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        // página de documentação: Swagger UI servido pela própria aplicação (scripts só da origem)
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; script-src 'self'; "
                                + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; "
                                + "connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'none'"))
                        .permissionsPolicyHeader(p -> p.policy("camera=(), microphone=(), geolocation=(), payment=()")))
                .addFilterBefore(new PublicApiRequestFilter(authentication, metering, versions, json, clock),
                        AuthorizationFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers(root + "/docs", root + "/docs/**", root + "/versions", root + "/changelog",
                                root + "/v*/openapi.json").permitAll()
                        .anyRequest().hasAuthority(INTEGRATION_AUTHORITY))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> {
                            res.setStatus(401);
                            res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                            res.getWriter().write("{\"status\":401,\"code\":\"auth.unauthenticated\","
                                    + "\"detail\":\"Token de integração obrigatório\"}");
                        })
                        .accessDeniedHandler((req, res, ex) -> {
                            res.setStatus(403);
                            res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                            res.getWriter().write("{\"status\":403,\"code\":\"access.denied\",\"detail\":\"Acesso negado\"}");
                        }));
        return http.build();
    }
}
