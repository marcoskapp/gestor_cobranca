package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.ProviderContext;
import br.com.gestorcobranca.telephony.domain.model.ProviderType;
import br.com.gestorcobranca.telephony.domain.model.TelephonyAccount;
import br.com.gestorcobranca.telephony.domain.repository.TelephonyAccountRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Conta e adaptador para discar: a conta ativa do credor e, sem ela, a conta padrão da
 * assessoria (número de origem por credor). Sem conta → {@code 409 telephony.not_configured}.
 */
@Component
class ProviderResolver {

    private final TelephonyAccountRepository accounts;
    private final Map<ProviderType, TelephonyProvider> providers;
    private final TelephonyCredentialCodec credentialCodec;

    ProviderResolver(TelephonyAccountRepository accounts, List<TelephonyProvider> providers,
                     TelephonyCredentialCodec credentialCodec) {
        this.accounts = accounts;
        this.providers = providers.stream()
                .collect(Collectors.toUnmodifiableMap(TelephonyProvider::provider, Function.identity()));
        this.credentialCodec = credentialCodec;
    }

    Optional<TelephonyAccount> findAccountFor(UUID creditorId) {
        Optional<TelephonyAccount> own = creditorId == null ? Optional.empty() : accounts.findActiveForCreditor(creditorId);
        return own.isPresent() ? own : accounts.findActiveDefault();
    }

    TelephonyAccount accountFor(UUID creditorId) {
        return findAccountFor(creditorId).filter(a -> providers.containsKey(a.getProvider()))
                .orElseThrow(() -> new ConflictException("telephony.not_configured",
                        "Nenhuma conta de telefonia ativa para o credor"));
    }

    Optional<TelephonyProvider> provider(ProviderType type) {
        return Optional.ofNullable(providers.get(type));
    }

    ProviderContext context(TelephonyAccount account) {
        return new ProviderContext(account.getId(), credentialCodec.decode(account.getCredentials()),
                account.getCallerId());
    }
}
