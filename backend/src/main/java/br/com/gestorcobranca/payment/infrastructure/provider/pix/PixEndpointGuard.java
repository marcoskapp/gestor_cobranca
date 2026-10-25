package br.com.gestorcobranca.payment.infrastructure.provider.pix;

import br.com.gestorcobranca.payment.application.PaymentProperties;
import br.com.gestorcobranca.payment.application.port.out.PixProvider.ProviderException;
import br.com.gestorcobranca.payment.domain.service.PixEndpointRules;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Conferência no momento da chamada (revisão de segurança A9): o host da URL do PSP precisa estar na
 * lista de hosts permitidos (quando configurada) e <strong>todos</strong> os endereços em que ele resolve
 * precisam ser públicos — um nome que aponte para a VPC, para o próprio host ou para os metadados da
 * instância é recusado, inclusive se o DNS mudar depois do cadastro. O certificado do servidor continua
 * validado pela cadeia pública da JVM, o que impede que um serviço interno se passe pelo nome do PSP
 * entre a conferência e a conexão.
 */
@Component
class PixEndpointGuard {

    private static final Logger LOG = LoggerFactory.getLogger(PixEndpointGuard.class);

    /** Resolução de nomes (substituível em testes). */
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final List<String> allowedHosts;
    private final Resolver resolver;
    private final MeterRegistry meterRegistry;

    @Autowired
    PixEndpointGuard(PaymentProperties properties, MeterRegistry meterRegistry) {
        this(properties.pix().allowedHosts(), InetAddress::getAllByName, meterRegistry);
    }

    PixEndpointGuard(List<String> allowedHosts, Resolver resolver, MeterRegistry meterRegistry) {
        this.allowedHosts = List.copyOf(allowedHosts);
        this.resolver = resolver;
        this.meterRegistry = meterRegistry;
    }

    void requireAllowed(String url) {
        URI uri = URI.create(url);
        String host = uri.getHost();
        if (host == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw refused("invalid", url, "URL do PSP inválida");
        }
        if (!PixEndpointRules.hostAllowed(host, allowedHosts)) {
            throw refused("host_not_allowed", host, "Host do PSP fora da lista permitida");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new ProviderException("PIX_UNAVAILABLE", "Host do PSP não resolvido", true, e);
        }
        for (InetAddress address : addresses) {
            if (PixEndpointRules.isInternal(address)) {
                throw refused("internal_address", host, "Host do PSP resolve para endereço interno");
            }
        }
    }

    private ProviderException refused(String reason, String target, String message) {
        meterRegistry.counter("payment.pix.endpoint_refused", "reason", reason).increment();
        LOG.atWarn().addKeyValue("provider", BacenPixProvider.PROVIDER).addKeyValue("reason", reason)
                .addKeyValue("host", target).addKeyValue("securityAlert", true)
                .log("Chamada a PSP recusada: endereço não permitido");
        return new ProviderException("PIX_ENDPOINT_FORBIDDEN", message, false, null);
    }
}
