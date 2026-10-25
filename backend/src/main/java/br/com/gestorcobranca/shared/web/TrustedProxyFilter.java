package br.com.gestorcobranca.shared.web;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Único ponto em que a aplicação interpreta cabeçalhos de proxy. Substitui o
 * {@code ForwardedHeaderFilter} do Spring ({@code server.forward-headers-strategy: framework}), que
 * confia em qualquer cliente e usa o valor mais à esquerda — forjável.
 * <p>
 * Regras:
 * <ul>
 *   <li>só quando a conexão TCP vem de um proxy confiável ({@code app.http.trusted-proxies}, as
 *       sub-redes do ALB) o {@code X-Forwarded-For} é lido, da <strong>direita</strong> para a esquerda,
 *       pulando saltos confiáveis: o primeiro IP não confiável é o cliente (o ALB acrescenta o IP real
 *       à direita; o que o cliente mandou fica à esquerda e é ignorado);</li>
 *   <li>{@code Forwarded} (RFC 7239), {@code X-Forwarded-Host}, {@code X-Real-IP} e afins nunca são
 *       usados e ficam ocultos para o restante da aplicação; cabeçalhos de mTLS do ALB
 *       ({@code X-Amzn-Mtls-*}) ficam ocultos quando a conexão não vem do ALB;</li>
 *   <li>um valor que não seja IP literal não é usado: a requisição segue com o IP da conexão
 *       e o MDC {@code clientIp} fica vazio (logs de acesso e auditoria gravam sem IP, nunca descartam
 *       o evento) — métrica {@code http.forwarded.invalid_client_ip}.</li>
 * </ul>
 * Esquema e porta ({@code X-Forwarded-Proto}/{@code -Port}, definidos pelo ALB) seguem a mesma regra
 * de confiança. Roda antes de todos os filtros (inclusive o de correlação, que grava o IP no MDC).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TrustedProxyFilter extends OncePerRequestFilter {

    /** Atributo da requisição com o IP do cliente validado (ausente se inválido). */
    public static final String CLIENT_IP_ATTRIBUTE = TrustedProxyFilter.class.getName() + ".clientIp";

    private static final Set<String> ALWAYS_HIDDEN = Set.of("forwarded", "x-forwarded-for", "x-forwarded-host",
            "x-forwarded-proto", "x-forwarded-port", "x-forwarded-prefix", "x-forwarded-ssl", "x-forwarded-server",
            "x-real-ip", "x-client-ip", "true-client-ip", "client-ip");
    private static final String ALB_MTLS_PREFIX = "x-amzn-mtls-";

    private final List<CidrRange> trusted;
    private final MeterRegistry meterRegistry;

    public TrustedProxyFilter(HttpProperties properties, MeterRegistry meterRegistry) {
        this.trusted = properties.trustedProxyRanges();
        this.meterRegistry = meterRegistry;
    }

    /** Resultado da resolução: IP do cliente (nulo se inválido), IP usado como remoteAddr e esquema/porta. */
    record Resolution(String clientIp, String remoteAddr, boolean viaProxy, String scheme, int port) {
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(wrap(request), response);
    }

    @Override
    protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        chain.doFilter(wrap(request), response);
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    private HttpServletRequest wrap(HttpServletRequest request) {
        Resolution resolution = resolve(request);
        if (resolution.clientIp() == null) {
            meterRegistry.counter("http.forwarded.invalid_client_ip").increment();
            request.removeAttribute(CLIENT_IP_ATTRIBUTE);
        } else {
            request.setAttribute(CLIENT_IP_ATTRIBUTE, resolution.clientIp());
        }
        return new ProxiedRequest(request, resolution);
    }

    Resolution resolve(HttpServletRequest request) {
        String peer = ClientAddresses.parse(request.getRemoteAddr()).orElse(null);
        String scheme = request.getScheme();
        int port = request.getServerPort();
        if (peer == null || !isTrusted(peer)) {
            return new Resolution(peer, request.getRemoteAddr(), false, scheme, port);
        }
        List<String> hops = values(request, "X-Forwarded-For");
        String client = peer;
        boolean invalid = false;
        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = ClientAddresses.parse(hops.get(i)).orElse(null);
            if (hop == null) {
                invalid = true;
                break;
            }
            client = hop;
            if (!isTrusted(hop)) {
                break;
            }
        }
        List<String> protos = values(request, "X-Forwarded-Proto");
        if (!protos.isEmpty()) {
            String proto = protos.getLast().toLowerCase(Locale.ROOT);
            if (proto.equals("https") || proto.equals("http")) {
                scheme = proto;
                port = proto.equals("https") ? 443 : 80;
            }
        }
        List<String> ports = values(request, "X-Forwarded-Port");
        if (!ports.isEmpty()) {
            try {
                int p = Integer.parseInt(ports.getLast());
                if (p > 0 && p <= 65_535) {
                    port = p;
                }
            } catch (NumberFormatException ignored) {
                // porta inválida: mantém a do esquema
            }
        }
        return new Resolution(invalid ? null : client, invalid ? peer : client, true, scheme, port);
    }

    private boolean isTrusted(String literal) {
        for (CidrRange range : trusted) {
            if (range.contains(literal)) {
                return true;
            }
        }
        return false;
    }

    /** Valores de um cabeçalho repetível e separado por vírgulas, na ordem de chegada. */
    private static List<String> values(HttpServletRequest request, String name) {
        List<String> result = new ArrayList<>();
        Enumeration<String> headers = request.getHeaders(name);
        while (headers != null && headers.hasMoreElements()) {
            for (String part : headers.nextElement().split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    /** Requisição vista pela aplicação: IP, esquema e porta resolvidos; cabeçalhos de proxy ocultos. */
    static final class ProxiedRequest extends HttpServletRequestWrapper {

        private final Resolution resolution;

        ProxiedRequest(HttpServletRequest request, Resolution resolution) {
            super(request);
            this.resolution = resolution;
        }

        private boolean hidden(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            return ALWAYS_HIDDEN.contains(lower) || (!resolution.viaProxy() && lower.startsWith(ALB_MTLS_PREFIX));
        }

        @Override
        public String getRemoteAddr() {
            return resolution.remoteAddr();
        }

        @Override
        public String getRemoteHost() {
            return resolution.remoteAddr();
        }

        @Override
        public String getScheme() {
            return resolution.scheme();
        }

        @Override
        public boolean isSecure() {
            return "https".equals(resolution.scheme()) || (!resolution.viaProxy() && super.isSecure());
        }

        @Override
        public int getServerPort() {
            return resolution.port();
        }

        @Override
        public StringBuffer getRequestURL() {
            StringBuffer url = new StringBuffer();
            String scheme = getScheme();
            int port = getServerPort();
            url.append(scheme).append("://").append(getServerName());
            if (!(("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80))) {
                url.append(':').append(port);
            }
            url.append(getRequestURI());
            return url;
        }

        @Override
        public String getHeader(String name) {
            return hidden(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return hidden(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public long getDateHeader(String name) {
            return hidden(name) ? -1 : super.getDateHeader(name);
        }

        @Override
        public int getIntHeader(String name) {
            return hidden(name) ? -1 : super.getIntHeader(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = new ArrayList<>();
            Enumeration<String> all = super.getHeaderNames();
            while (all != null && all.hasMoreElements()) {
                String name = all.nextElement();
                if (!hidden(name)) {
                    names.add(name);
                }
            }
            return Collections.enumeration(names);
        }
    }
}
