package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.communication.application.port.out.ChannelGateway;
import br.com.gestorcobranca.communication.domain.model.ChannelAccount;
import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.ProviderType;
import br.com.gestorcobranca.communication.domain.repository.ChannelAccountRepository;
import br.com.gestorcobranca.shared.domain.ConflictException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Remetente por credor: conta ativa do credor no canal ou, sem ela, a conta padrão da assessoria;
 * e o adaptador do provedor da conta.
 */
@Component
class ChannelResolver {

    private final ChannelAccountRepository accounts;
    private final Map<ProviderType, ChannelGateway> gateways;

    ChannelResolver(ChannelAccountRepository accounts, List<ChannelGateway> gateways) {
        this.accounts = accounts;
        this.gateways = gateways.stream().collect(Collectors.toUnmodifiableMap(ChannelGateway::provider, Function.identity()));
    }

    ChannelAccount resolve(ChannelType channel, UUID creditorId) {
        return accounts.findActiveForCreditor(channel, creditorId)
                .or(() -> accounts.findActiveDefault(channel))
                .orElseThrow(() -> new ConflictException("channel.not_configured",
                        "Nenhuma conta ativa do canal %s para o credor ou a assessoria".formatted(channel)));
    }

    Optional<ChannelGateway> gateway(ProviderType provider) {
        return Optional.ofNullable(gateways.get(provider));
    }
}
