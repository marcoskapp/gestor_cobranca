package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.ProviderContext;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.Readiness;
import br.com.gestorcobranca.telephony.domain.model.ProviderType;
import br.com.gestorcobranca.telephony.domain.model.TelephonyAccount;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Adaptadores de provedor por tipo e se cada um pode discar neste ambiente: {@code READY} sempre;
 * {@code CONTRACT_PENDING} somente com o provedor em {@code app.telephony.homologation.providers};
 * {@code NOT_IMPLEMENTED} (ou sem adaptador) nunca. Contexto (credenciais decodificadas) por conta.
 */
@Component
class ProviderResolver {

    private final Map<ProviderType, TelephonyProvider> providers;
    private final TelephonyCredentialCodec credentialCodec;
    private final TelephonyProperties properties;

    ProviderResolver(List<TelephonyProvider> providers, TelephonyCredentialCodec credentialCodec,
                     TelephonyProperties properties) {
        this.providers = providers.stream()
                .collect(Collectors.toUnmodifiableMap(TelephonyProvider::provider, Function.identity()));
        this.credentialCodec = credentialCodec;
        this.properties = properties;
    }

    Readiness readiness(ProviderType type) {
        TelephonyProvider adapter = providers.get(type);
        return adapter == null ? Readiness.NOT_IMPLEMENTED : adapter.readiness();
    }

    /** O provedor disca neste ambiente (adaptador pronto ou contrato pendente em homologação). */
    boolean canDial(ProviderType type) {
        Readiness readiness = readiness(type);
        return readiness == Readiness.READY
                || (readiness == Readiness.CONTRACT_PENDING && properties.homologation().allows(type.name()));
    }

    /** Conta ativa cujo provedor disca neste ambiente. */
    boolean usable(TelephonyAccount account) {
        return account != null && account.isActive() && canDial(account.getProvider());
    }

    /** Adaptador que pode discar (vazio para provedor indisponível neste ambiente). */
    Optional<TelephonyProvider> provider(ProviderType type) {
        return canDial(type) ? Optional.ofNullable(providers.get(type)) : Optional.empty();
    }

    /** Adaptador existente, pronto ou não (validação de credenciais). */
    Optional<TelephonyProvider> adapter(ProviderType type) {
        return Optional.ofNullable(providers.get(type));
    }

    ProviderContext context(TelephonyAccount account) {
        return new ProviderContext(account.getId(), credentialCodec.decode(account.getCredentials()),
                account.getCallerId());
    }
}
