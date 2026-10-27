package br.com.gestorcobranca.telephony.infrastructure.provider;

import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.ProviderRejectedException;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.RecordingMedia;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.TransientProviderException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Cliente HTTP dos adaptadores de CPaaS com API pública (Twilio, Plivo): endereço da API fixo por
 * ambiente e conferido contra a lista de hosts permitidos na subida (proteção contra SSRF), sem
 * redirecionamento, timeouts explícitos, métricas {@code integration.calls{provider,operation,outcome}}
 * e {@code integration.calls.duration{provider,operation}} e classificação de erros:
 * <ul>
 *   <li>conexão recusada, tempo de conexão esgotado, host não resolvido, 429 e 503 →
 *       {@link TransientProviderException#notProcessed} (o pedido não chegou a criar nada);</li>
 *   <li>tempo de leitura esgotado e outros 5xx → transitório ambíguo;</li>
 *   <li>4xx → {@link Rejected} com código estável derivado do corpo (nunca o texto livre).</li>
 * </ul>
 * Nunca registra número, ramal, token ou segredo.
 */
public final class ProviderHttp {

    private final String provider;
    private final URI base;
    private final RestClient client;
    private final MeterRegistry meterRegistry;
    private final BiFunction<Integer, String, String> errorCode;

    /**
     * @param provider  nome da métrica ({@code twilio}, {@code plivo})
     * @param errorCode código estável a partir de (status, corpo da resposta de erro)
     */
    public ProviderHttp(String provider, String apiBaseUrl, List<String> allowedHosts, boolean allowInsecureHttp,
                        Duration connectTimeout, Duration readTimeout, MeterRegistry meterRegistry,
                        BiFunction<Integer, String, String> errorCode) {
        this.provider = provider;
        this.base = validatedBase(apiBaseUrl, allowedHosts, allowInsecureHttp, provider);
        HttpClient http = HttpClient.newBuilder().connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        this.client = RestClient.builder().requestFactory(factory).build();
        this.meterRegistry = meterRegistry;
        this.errorCode = errorCode;
    }

    public RestClient client() {
        return client;
    }

    /** Endereço da API + caminho já codificado (segmentos validados pelo chamador). */
    public URI uri(String encodedPath) {
        String basePath = base.getRawPath() == null ? "" : base.getRawPath().replaceAll("/+$", "");
        return URI.create(base.getScheme() + "://" + base.getRawAuthority() + basePath + encodedPath);
    }

    /** Executa com métrica e classificação de erros. */
    public <T> T call(String operation, Supplier<T> action) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            T result = action.get();
            outcome = "success";
            return result;
        } catch (RestClientResponseException e) {
            RuntimeException mapped = statusError(e.getStatusCode().value(), e.getResponseBodyAsString());
            outcome = mapped instanceof Rejected ? "rejected" : "transient";
            throw mapped;
        } catch (ResourceAccessException e) {
            boolean notProcessed = notProcessed(e);
            outcome = notProcessed ? "unreachable" : "timeout";
            throw notProcessed ? TransientProviderException.notProcessed(label() + " inacessível", e)
                    : new TransientProviderException("Tempo esgotado na " + label(), e);
        } catch (Rejected | TransientProviderException e) {
            outcome = e instanceof Rejected ? "rejected" : "transient";
            throw e;
        } finally {
            meterRegistry.counter("integration.calls", "provider", provider, "operation", operation, "outcome", outcome)
                    .increment();
            sample.stop(meterRegistry.timer("integration.calls.duration", "provider", provider, "operation", operation));
        }
    }

    /** {@link Rejected} vira {@link ProviderRejectedException} (operações sobre chamada/gravação existente). */
    public <T> T rejectedAsException(Supplier<T> action) {
        try {
            return action.get();
        } catch (Rejected e) {
            throw new ProviderRejectedException(e.code(), label() + " recusou o pedido (HTTP " + e.status() + ")");
        }
    }

    /** Resposta binária (gravação): não-200 vira o mesmo erro do {@code retrieve()}. */
    public static RecordingMedia media(ClientHttpResponse res, String label) throws IOException {
        int status = res.getStatusCode().value();
        if (status != 200) {
            byte[] errorBody = res.getBody().readNBytes(4096);
            res.close();
            throw new RestClientResponseException(label + " HTTP " + status, status, "", null, errorBody,
                    StandardCharsets.UTF_8);
        }
        MediaType type = res.getHeaders().getContentType();
        long size = res.getHeaders().getContentLength();
        InputStream body = res.getBody();
        return new RecordingMedia(type == null ? null : type.getType() + "/" + type.getSubtype(), size,
                new FilterInputStream(body) {
                    @Override
                    public void close() throws IOException {
                        try {
                            super.close();
                        } finally {
                            res.close();
                        }
                    }
                });
    }

    private RuntimeException statusError(int status, String body) {
        if (status == 429 || status == 503) {
            return TransientProviderException.notProcessed(label() + " HTTP " + status, null);
        }
        if (status >= 500) {
            return new TransientProviderException(label() + " HTTP " + status);
        }
        return new Rejected(status, errorCode.apply(status, body));
    }

    private String label() {
        return provider.substring(0, 1).toUpperCase(Locale.ROOT) + provider.substring(1);
    }

    static boolean notProcessed(ResourceAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException
                    || t instanceof UnknownHostException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Endereço da API validado na subida: HTTPS (HTTP só com a opção de teste), sem usuário, consulta
     * ou fragmento, e host na lista permitida (exato, ou sufixo iniciado por ponto).
     */
    public static URI validatedBase(String value, List<String> allowedHosts, boolean allowInsecureHttp, String provider) {
        URI uri;
        try {
            uri = new URI(value == null ? "" : value.trim());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("app.telephony." + provider + ".api-base-url inválido");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("https".equals(scheme) || ("http".equals(scheme) && allowInsecureHttp))) {
            throw new IllegalStateException("app.telephony." + provider + ".api-base-url precisa usar HTTPS");
        }
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalStateException("app.telephony." + provider + ".api-base-url inválido");
        }
        if (!hostAllowed(uri.getHost(), allowedHosts)) {
            throw new IllegalStateException("app.telephony." + provider + ".api-base-url fora de allowed-hosts");
        }
        return uri;
    }

    public static boolean hostAllowed(String rawHost, List<String> allowedHosts) {
        if (rawHost == null || rawHost.isBlank()) {
            return false;
        }
        String host = IDN.toASCII(rawHost).toLowerCase(Locale.ROOT);
        return allowedHosts.stream().map(s -> s.toLowerCase(Locale.ROOT).trim()).filter(s -> !s.isEmpty())
                .anyMatch(s -> s.startsWith(".") ? host.endsWith(s) && host.length() > s.length() : host.equals(s));
    }

    /** Código estável e curto (nunca o texto livre do fornecedor). */
    public static String code(String raw) {
        String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        return code.isEmpty() ? "UNKNOWN" : code.substring(0, Math.min(40, code.length()));
    }

    /** Recusa 4xx (vira {@code DialResult.rejected} ou {@link ProviderRejectedException}). */
    public static final class Rejected extends RuntimeException {

        private final int status;
        private final String code;

        public Rejected(int status, String code) {
            super(code, null, false, false);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }
}
