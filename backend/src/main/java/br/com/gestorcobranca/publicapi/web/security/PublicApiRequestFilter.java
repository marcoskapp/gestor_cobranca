package br.com.gestorcobranca.publicapi.web.security;

import br.com.gestorcobranca.publicapi.application.port.in.ApiVersionUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.ApiVersionUseCases.ApiVersionView;
import br.com.gestorcobranca.publicapi.application.port.in.gateway.IntegrationAuthentication;
import br.com.gestorcobranca.publicapi.application.port.in.gateway.IntegrationAuthentication.Authenticated;
import br.com.gestorcobranca.publicapi.application.port.in.gateway.IntegrationAuthentication.RateDecision;
import br.com.gestorcobranca.publicapi.application.port.in.gateway.IntegrationAuthentication.Rejected;
import br.com.gestorcobranca.publicapi.application.port.in.gateway.PublicApiMetering;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.logging.MdcKeys;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.web.TrustedProxyFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filtro único da cadeia {@code /public-api/**}, nesta ordem:
 * <ol>
 *   <li><b>versão</b> ({@code /public-api/v<n>/…}): inexistente → 404; desligada → 410; depreciada → cabeçalhos
 *       {@code Deprecation} (RFC 9745), {@code Sunset} (RFC 8594) e {@code Link} para o guia de migração;</li>
 *   <li><b>autenticação</b> do token ({@code Authorization: Bearer gck_…}), exceto rotas públicas de documentação: o
 *       ator {@code INTEGRATION} com o escopo do credor do token vira a autenticação da requisição (RLS, escopo por
 *       credor e {@code @PreAuthorize} por escopo);</li>
 *   <li><b>limite de taxa</b> por token → 429 com {@code Retry-After} e cabeçalhos {@code RateLimit-*};</li>
 *   <li><b>medição</b> dos bytes trafegados, latência, status e registros, entregue ao fim da requisição
 *       ({@link PublicApiMetering}).</li>
 * </ol>
 * Erros escritos aqui seguem RFC 9457. Não é registrado como filtro de servlet: só existe dentro da cadeia própria.
 */
public class PublicApiRequestFilter extends OncePerRequestFilter {

    public static final String RATE_LIMIT_LIMIT = "RateLimit-Limit";
    public static final String RATE_LIMIT_REMAINING = "RateLimit-Remaining";
    public static final String RATE_LIMIT_RESET = "RateLimit-Reset";

    /** Rotas públicas (sem token): documentação, contrato, versões e changelog. */
    private static final Pattern PUBLIC_ROUTE = Pattern.compile(
            "/public-api(/docs(/.*)?|/versions|/changelog|/v[1-9][0-9]*/openapi\\.json)/?");
    private static final Pattern VERSIONED = Pattern.compile("/public-api/(v[1-9][0-9]*)(/.*)?");
    private static final String BEARER = "Bearer ";

    private final IntegrationAuthentication authentication;
    private final PublicApiMetering metering;
    private final ApiVersionUseCases versions;
    private final JsonCodec json;
    private final Clock clock;

    public PublicApiRequestFilter(IntegrationAuthentication authentication, PublicApiMetering metering,
                                  ApiVersionUseCases versions, JsonCodec json, Clock clock) {
        this.authentication = authentication;
        this.metering = metering;
        this.versions = versions;
        this.json = json;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Matcher versioned = VERSIONED.matcher(path);
        if (versioned.matches() && !applyVersionPolicy(versioned.group(1), response)) {
            return;
        }
        if (PUBLIC_ROUTE.matcher(path).matches()) {
            chain.doFilter(request, response);
            return;
        }
        if (!versioned.matches()) {
            ProblemWriter.write(response, json, 404, "public_api.version_required",
                    "Informe a versão da API no caminho (ex.: /public-api/v1/...)");
            return;
        }
        long started = System.nanoTime();
        String clientIp = clientIp(request);
        IntegrationAuthentication.Result result = authentication.authenticate(bearer(request), clientIp);
        if (result instanceof Rejected rejected) {
            if (rejected.status() == 401) {
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"gestor-cobranca-public-api\"");
                ProblemWriter.write(response, json, 401, "auth.unauthenticated",
                        "Token de integração ausente, inválido, revogado ou expirado");
            } else {
                ProblemWriter.write(response, json, 403, "access.denied", "Acesso negado para este token");
            }
            return;
        }
        Authenticated auth = (Authenticated) result;
        CountingStreams.Request countedRequest = new CountingStreams.Request(request);
        CountingStreams.Response countedResponse = new CountingStreams.Response(response);
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(ActorAuthentication.integration(auth.actor(), auth.scopes()));
        SecurityContextHolder.setContext(context);
        int status = 500;
        try {
            RateDecision rate = authentication.acquire(auth.tokenId(), auth.rateLimitPerMinute());
            response.setHeader(RATE_LIMIT_LIMIT, String.valueOf(rate.limit()));
            response.setHeader(RATE_LIMIT_REMAINING, String.valueOf(rate.remaining()));
            response.setHeader(RATE_LIMIT_RESET, String.valueOf(rate.resetSeconds()));
            if (!rate.allowed()) {
                response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(rate.resetSeconds()));
                ProblemWriter.write(countedResponse, json, 429, "rate_limited",
                        "Limite de requisições do token excedido; tente novamente após o intervalo indicado");
                status = 429;
                return;
            }
            chain.doFilter(countedRequest, countedResponse);
            status = response.getStatus();
        } finally {
            SecurityContextHolder.setContext(previous);
            long bytesIn = Math.max(countedRequest.bytesRead(), Math.max(0, request.getContentLengthLong()));
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            metering.record(new PublicApiMetering.RequestMeasurement(auth.actor().tenantId(), auth.creditorId(),
                    auth.tokenId(), auth.prefix(), request.getMethod(),
                    pattern == null ? (status == 429 ? path : "UNMATCHED") : pattern.toString(), status, bytesIn,
                    countedResponse.bytesWritten(), UsageRecords.of(request), (System.nanoTime() - started) / 1_000_000,
                    clientIp, Instant.now(clock)));
        }
    }

    /** @return false se a resposta já foi escrita (versão inexistente ou desligada) */
    private boolean applyVersionPolicy(String code, HttpServletResponse response) throws IOException {
        Optional<ApiVersionView> version = versions.version(code);
        if (version.isEmpty()) {
            ProblemWriter.write(response, json, 404, "public_api.version_unknown", "Versão da API inexistente: " + code);
            return false;
        }
        ApiVersionView v = version.get();
        if ("RETIRED".equals(v.status())) {
            ProblemWriter.write(response, json, 410, "public_api.version_retired",
                    "Versão %s desligada; use a versão vigente (veja /public-api/versions)".formatted(code));
            return false;
        }
        if ("DEPRECATED".equals(v.status())) {
            response.setHeader("Deprecation", "@" + v.deprecated().getEpochSecond());
            response.setHeader("Sunset", DateTimeFormatter.RFC_1123_DATE_TIME.format(v.sunset().atOffset(ZoneOffset.UTC)));
            String guide = v.migrationGuide() == null ? "/public-api/docs/changelog" : v.migrationGuide();
            response.addHeader(HttpHeaders.LINK, "<" + guide + ">; rel=\"deprecation\"; type=\"text/html\"");
        }
        return true;
    }

    private static String bearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.regionMatches(true, 0, BEARER, 0, BEARER.length())
                ? header.substring(BEARER.length()).strip() : null;
    }

    /** IP já validado pelo {@code TrustedProxyFilter} (o do ALB é resolvido pelo X-Forwarded-For confiável). */
    private static String clientIp(HttpServletRequest request) {
        Object ip = request.getAttribute(TrustedProxyFilter.CLIENT_IP_ATTRIBUTE);
        return ip != null ? ip.toString() : MDC.get(MdcKeys.CLIENT_IP);
    }
}
