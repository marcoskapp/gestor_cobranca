package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.application.port.out.PixProvider;
import br.com.gestorcobranca.payment.application.port.out.PixProvider.PixConnection;
import br.com.gestorcobranca.payment.domain.model.PixAccount;
import br.com.gestorcobranca.payment.domain.model.PixProviderType;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolve o adaptador da API Pix de uma conta e monta a conexão com os segredos decifrados (só em
 * memória, durante a chamada). O simulador só existe quando habilitado (local/teste).
 */
@Component
class PixGateway {

    private final Map<PixProviderType, PixProvider> providers;
    private final PixCredentialCodec credentials;

    PixGateway(List<PixProvider> providers, PixCredentialCodec credentials) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(PixProvider::type, Function.identity()));
        this.credentials = credentials;
    }

    boolean available(PixProviderType type) {
        return providers.containsKey(type);
    }

    PixProvider provider(PixAccount account) {
        return Optional.ofNullable(providers.get(account.getProvider())).orElseThrow(() ->
                new BusinessRuleException("pix_account.provider_unavailable",
                        "Provedor Pix %s indisponível neste ambiente".formatted(account.getProvider())));
    }

    PixConnection connection(PixAccount account) {
        return new PixConnection(account.getId(), account.getApiBaseUrl(), account.getTokenUrl(), account.getPixKey(),
                credentials.decode(account.getCredentials()), account.getCertificate(), account.getCredentialsUpdatedAt());
    }
}
