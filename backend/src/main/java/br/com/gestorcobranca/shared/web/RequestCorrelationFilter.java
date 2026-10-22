package br.com.gestorcobranca.shared.web;

import br.com.gestorcobranca.shared.logging.MdcKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Primeiro filtro da cadeia. Estabelece a correlação da requisição e emite uma linha de log
 * técnico por requisição ({@code http.request}) com rota, status e duração — base para
 * análise de desempenho no CloudWatch Logs Insights (percentis por rota, erros por tenant).
 * <p>
 * Métricas agregadas de latência ficam a cargo do Micrometer ({@code http.server.requests}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final Logger LOG = LoggerFactory.getLogger("http.request");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || !SAFE_REQUEST_ID.matcher(requestId).matches()) {
            requestId = UUID.randomUUID().toString();
        }
        MDC.put(MdcKeys.REQUEST_ID, requestId);
        MDC.put(MdcKeys.CLIENT_IP, request.getRemoteAddr());
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null) {
            MDC.put(MdcKeys.USER_AGENT, userAgent.length() > 512 ? userAgent.substring(0, 512) : userAgent);
        }
        response.setHeader(REQUEST_ID_HEADER, requestId);

        long start = System.nanoTime();
        Throwable failure = null;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            logRequest(request, response, durationMs, failure);
            MDC.clear();
        }
    }

    private static void logRequest(HttpServletRequest request, HttpServletResponse response, long durationMs, Throwable failure) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = pattern == null ? "UNMATCHED" : pattern.toString();
        int status = failure == null ? response.getStatus() : 500;
        var builder = (status >= 500 ? LOG.atError() : status >= 400 ? LOG.atWarn() : LOG.atInfo())
                .addKeyValue("http.method", request.getMethod())
                .addKeyValue("http.route", route)
                .addKeyValue("http.status", status)
                .addKeyValue("durationMs", durationMs);
        if (durationMs > 2_000) {
            builder.addKeyValue("slow", true);
        }
        builder.log("{} {} {} {}ms", request.getMethod(), route, status, durationMs);
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/actuator/health");
    }
}
