package br.com.gestorcobranca.identity.infrastructure.config;

import br.com.gestorcobranca.identity.application.IdentityProperties;
import br.com.gestorcobranca.shared.logging.MdcKeys;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.BrowserPolicyContribution;
import br.com.gestorcobranca.shared.security.PublicEndpoint;
import br.com.gestorcobranca.shared.security.TokenActorResolver;
import br.com.gestorcobranca.shared.security.TokenClaims;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cadeia de segurança HTTP da plataforma:
 * <ul>
 *   <li>API sem estado: autenticação por JWT RS256 ({@code Authorization: Bearer}); sem sessão HTTP.</li>
 *   <li>Cada JWT é convertido em {@link ActorAuthentication} por um {@link TokenActorResolver}
 *       conforme o tipo ({@code access} para usuários, {@code portal} para devedores), que
 *       também valida a sessão no servidor.</li>
 *   <li>Endpoints públicos são declarados pelos módulos como beans {@link PublicEndpoint}.</li>
 *   <li>Separação de sessões: {@code /api/portal/v1/**} só aceita o devedor autenticado no portal
 *       (token {@code portal}, audiência própria); as demais APIs recusam o devedor (403).</li>
 *   <li>Autorização fina por permissão com {@code @PreAuthorize} nos controllers.</li>
 *   <li>CSRF desabilitado: a API não usa cookies de autenticação, exceto o refresh token
 *       ({@code SameSite=Strict}, caminho restrito e verificação de origem no controller).</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);

    /** Authority do ator devedor ({@code ActorAuthentication} acrescenta {@code ACTOR_<tipo>}). */
    static final String DEBTOR_AUTHORITY = "ACTOR_" + Actor.ActorType.DEBTOR.name();

    /** API interna: autenticado por token de usuário (nunca o token do portal do devedor). */
    static final AuthorizationManager<RequestAuthorizationContext> INTERNAL_ONLY = (authentication, context) -> {
        Authentication auth = authentication.get();
        boolean allowed = auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)
                && auth.getAuthorities().stream().noneMatch(a -> DEBTOR_AUTHORITY.equals(a.getAuthority()));
        return new AuthorizationDecision(allowed);
    };

    private static String sources(List<BrowserPolicyContribution> policies,
                                  Function<BrowserPolicyContribution, List<String>> field) {
        return policies.stream().flatMap(p -> field.apply(p).stream()).distinct().sorted()
                .map(origin -> " " + origin).collect(Collectors.joining());
    }

    /** Tudo bloqueado, exceto os recursos liberados para a própria origem pelos módulos. */
    static String permissionsPolicy(List<String> selfFeatures) {
        return java.util.stream.Stream.of("camera", "microphone", "geolocation", "payment")
                .map(f -> f + (selfFeatures.contains(f) ? "=(self)" : "=()")).collect(Collectors.joining(", "));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // Argon2id: memória 64 MiB, 3 iterações, paralelismo 1 (OWASP Password Storage Cheat Sheet)
        return new Argon2PasswordEncoder(16, 32, 1, 65_536, 3);
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, List<PublicEndpoint> publicEndpoints,
                                    List<TokenActorResolver> resolvers, AccessLogger accessLogger,
                                    CurrentActor currentActor, IdentityProperties properties,
                                    List<BrowserPolicyContribution> browserPolicies) throws Exception {
        publicEndpoints.forEach(e -> LOG.info("Endpoint público: {} {} — {}",
                e.method() == null ? "*" : e.method(), e.pattern(), e.reason()));
        browserPolicies.forEach(p -> LOG.info("Política do navegador ampliada: connect-src {} media-src {} recursos {} — {}",
                p.connectSrc(), p.mediaSrc(), p.selfFeatures(), p.reason()));
        String extraConnect = sources(browserPolicies, BrowserPolicyContribution::connectSrc);
        String extraMedia = sources(browserPolicies, BrowserPolicyContribution::mediaSrc);
        List<String> selfFeatures = browserPolicies.stream().flatMap(p -> p.selfFeatures().stream()).distinct().toList();

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfiguration(properties)))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(c -> { })
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                // fontes e ícones são servidos pelo próprio backend (pacotes npm), sem CDN
                                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                                        + "font-src 'self'; img-src 'self' data:; "
                                        // player das gravações de chamada: áudio obtido com o token e tocado de blob: em memória;
                                        // origens de fornecedores só por BrowserPolicyContribution (ex.: SDK de voz WebRTC)
                                        + "media-src 'self' blob:" + extraMedia + "; "
                                        + "connect-src 'self'" + extraConnect
                                        + "; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"))
                        .permissionsPolicyHeader(p -> p.policy(permissionsPolicy(selfFeatures))))
                .authorizeHttpRequests(auth -> {
                    for (PublicEndpoint endpoint : publicEndpoints) {
                        auth.requestMatchers(matcher(endpoint.method(), endpoint.pattern())).permitAll();
                    }
                    auth.requestMatchers(matcher(HttpMethod.GET, "/actuator/health/**")).permitAll()
                            .requestMatchers(matcher(HttpMethod.GET, "/actuator/info")).permitAll()
                            .requestMatchers(matcher(null, "/actuator/**")).denyAll()
                            // API do portal: somente o devedor autenticado no portal (token de audiência própria)
                            .requestMatchers(matcher(null, ApiPaths.PORTAL + "/**")).hasAuthority(DEBTOR_AUTHORITY)
                            // demais APIs: autenticado e nunca um devedor do portal (sessões não se misturam)
                            .requestMatchers(matcher(null, "/api/**")).access(INTERNAL_ONLY)
                            .requestMatchers(matcher(null, "/v3/api-docs/**"), matcher(null, "/swagger-ui/**")).authenticated()
                            // aplicação Angular (estáticos) servida pelo próprio backend
                            .anyRequest().permitAll();
                })
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(actorConverter(resolvers)))
                        .authenticationEntryPoint((request, response, ex) -> {
                            accessLogger.log(AccessEvent.anonymous(AccessEventType.UNAUTHENTICATED, null, null,
                                    Map.of("route", request.getRequestURI(), "reason", ex.getClass().getSimpleName())));
                            writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "auth.unauthenticated",
                                    "Autenticação necessária");
                        })
                        .accessDeniedHandler((request, response, ex) -> {
                            currentActor.find().ifPresent(actor -> accessLogger.log(AccessEvent.of(
                                    AccessEventType.ACCESS_DENIED, actor, Map.of("route", request.getRequestURI()))));
                            writeProblem(response, HttpServletResponse.SC_FORBIDDEN, "access.denied", "Acesso negado");
                        }));
        return http.build();
    }

    private static PathPatternRequestMatcher matcher(HttpMethod method, String pattern) {
        return method == null
                ? PathPatternRequestMatcher.withDefaults().matcher(pattern)
                : PathPatternRequestMatcher.withDefaults().matcher(method, pattern);
    }

    /** Seleciona o resolvedor pelo tipo do token; sessão inválida resulta em 401. */
    private static Converter<Jwt, AbstractAuthenticationToken> actorConverter(List<TokenActorResolver> resolvers) {
        Map<String, TokenActorResolver> byType = resolvers.stream()
                .collect(Collectors.toUnmodifiableMap(TokenActorResolver::tokenType, Function.identity()));
        return jwt -> {
            TokenActorResolver resolver = byType.get(jwt.getClaimAsString(TokenClaims.TYPE));
            if (resolver == null) {
                // tokens de etapa (mfa, troca de senha) não autenticam chamadas à API
                throw new InvalidBearerTokenException("Tipo de token não aceito");
            }
            return resolver.resolve(jwt)
                    .map(actor -> new ActorAuthentication(actor, jwt.getTokenValue()))
                    .orElseThrow(() -> new InvalidBearerTokenException("Sessão encerrada"));
        };
    }

    private static CorsConfigurationSource corsConfiguration(IdentityProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.cors().allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id", "Idempotency-Key"));
        config.setExposedHeaders(List.of("X-Request-Id", "Content-Disposition"));
        config.setAllowCredentials(true);
        config.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    private static void writeProblem(HttpServletResponse response, int status, String code, String detail)
            throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        String requestId = MDC.get(MdcKeys.REQUEST_ID);
        response.getWriter().write("{\"status\":%d,\"code\":\"%s\",\"detail\":\"%s\",\"requestId\":\"%s\"}"
                .formatted(status, code, detail, requestId == null ? "" : requestId));
    }

    /** Endpoints de autenticação sem JWT (login, MFA, refresh, redefinição de senha). */
    @Bean
    PublicEndpoint authEndpoints() {
        return new PublicEndpoint(HttpMethod.POST, ApiPaths.V1 + "/auth/**", "Fluxo de autenticação (protegido por rate limit)");
    }
}
