package br.com.gestorcobranca.payment.infrastructure.provider.pix;

import br.com.gestorcobranca.payment.application.PaymentProperties;
import br.com.gestorcobranca.payment.application.port.out.PixProvider.PixConnection;
import br.com.gestorcobranca.payment.application.port.out.PixProvider.ProviderException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Clientes HTTP por conta Pix com TLS mútuo: chave e certificado do PKCS#12 da conta (decifrado só em
 * memória), cadeia do servidor validada contra o trust store padrão da JVM, TLS 1.2+, timeouts
 * explícitos. Um cliente por conta e versão das credenciais (a rotação gera um novo).
 */
@Component
class MutualTlsClientFactory implements PixHttpClientFactory {

    private final PaymentProperties properties;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    MutualTlsClientFactory(PaymentProperties properties) {
        this.properties = properties;
    }

    private record Cached(Instant credentialsUpdatedAt, RestClient client) {
    }

    @Override
    public RestClient client(PixConnection connection) {
        Cached cached = cache.get(connection.accountId());
        if (cached != null && Objects.equals(cached.credentialsUpdatedAt(), connection.credentialsUpdatedAt())) {
            return cached.client();
        }
        RestClient client = build(connection);
        cache.put(connection.accountId(), new Cached(connection.credentialsUpdatedAt(), client));
        return client;
    }

    private RestClient build(PixConnection connection) {
        if (connection.certificateBase64() == null) {
            throw new ProviderException("PIX_CERTIFICATE_MISSING", "Conta Pix sem certificado de cliente", false, null);
        }
        SSLContext ssl = sslContext(Base64.getDecoder().decode(connection.certificateBase64()),
                connection.credentials().getOrDefault("certificatePassword", ""));
        HttpClient http = HttpClient.newBuilder().sslContext(ssl).connectTimeout(properties.pix().connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.pix().readTimeout());
        return RestClient.builder().requestFactory(factory).build();
    }

    static SSLContext sslContext(byte[] pkcs12, String password) {
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            char[] secret = password == null ? new char[0] : password.toCharArray();
            keyStore.load(new ByteArrayInputStream(pkcs12), secret);
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(keyStore, secret);
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init((KeyStore) null);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);
            return context;
        } catch (IOException | GeneralSecurityException e) {
            throw new ProviderException("PIX_CERTIFICATE_INVALID", "Certificado de cliente da conta Pix inválido", false, e);
        }
    }
}
