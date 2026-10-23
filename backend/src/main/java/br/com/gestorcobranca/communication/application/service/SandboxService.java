package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.communication.application.CommunicationProperties;
import br.com.gestorcobranca.communication.application.port.in.ProviderCallbackUseCases;
import br.com.gestorcobranca.communication.application.port.in.SandboxUseCases;
import br.com.gestorcobranca.communication.application.port.out.ProviderWebhookAdapter.InboundMessage;
import br.com.gestorcobranca.communication.domain.model.ChannelAccount;
import br.com.gestorcobranca.communication.domain.model.ProviderType;
import br.com.gestorcobranca.communication.domain.repository.ChannelAccountRepository;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Simulação de mensagem recebida numa conta sandbox: valida a conta no contexto do usuário e
 * processa como faria o webhook do provedor (ator de sistema, transação própria).
 */
@Service
class SandboxService implements SandboxUseCases {

    private final CommunicationProperties properties;
    private final ChannelAccountRepository accounts;
    private final ProviderCallbackUseCases callbacks;
    private final TenantExecution tenantExecution;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final Clock clock;

    SandboxService(CommunicationProperties properties, ChannelAccountRepository accounts,
                   ProviderCallbackUseCases callbacks, TenantExecution tenantExecution, AccessScope accessScope,
                   CurrentActor currentActor, Clock clock) {
        this.properties = properties;
        this.accounts = accounts;
        this.callbacks = callbacks;
        this.tenantExecution = tenantExecution;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @Override
    public void simulateInbound(UUID channelAccountId, String from, String text) {
        if (!properties.sandbox().enabled()) {
            throw new ConflictException("sandbox.disabled", "Simulação disponível apenas em ambiente local ou de teste");
        }
        Guard.require(from != null && !from.isBlank(), "sandbox.from_required", "Remetente obrigatório");
        Guard.require(text != null && text.length() <= 4096, "sandbox.text_invalid", "Texto até 4096 caracteres");
        UUID tenantId = currentActor.tenantId();
        ChannelAccount account = tenantExecution.callAs(currentActor.get(), () -> accounts.findById(channelAccountId)
                .orElseThrow(() -> ResourceNotFoundException.of("channel_account", channelAccountId)));
        if (account.getCreditorId() == null) {
            accessScope.requireAllCreditors("channel_account");
        } else {
            accessScope.requireCreditor(account.getCreditorId());
        }
        Guard.requireState(account.getProvider() == ProviderType.SANDBOX, "sandbox.account_not_sandbox",
                "A conta não usa o provedor simulado");
        tenantExecution.inTenant(tenantId, "communication.sandbox_inbound", () -> callbacks.inbound(channelAccountId,
                new InboundMessage("sbx-in-" + UUID.randomUUID(), from, "text", text, Instant.now(clock))));
    }
}
