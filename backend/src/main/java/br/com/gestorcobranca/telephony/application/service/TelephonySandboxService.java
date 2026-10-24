package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.in.ProviderEventUseCases;
import br.com.gestorcobranca.telephony.application.port.in.TelephonySandboxUseCases;
import br.com.gestorcobranca.telephony.application.port.out.ProviderCallEvent;
import br.com.gestorcobranca.telephony.application.port.out.ProviderCallEvent.Type;
import br.com.gestorcobranca.telephony.domain.model.Call;
import br.com.gestorcobranca.telephony.domain.model.ProviderType;
import br.com.gestorcobranca.telephony.domain.model.TelephonyAccount;
import br.com.gestorcobranca.telephony.domain.repository.CallRepository;
import br.com.gestorcobranca.telephony.domain.repository.TelephonyAccountRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Simulação de eventos do provedor numa conta sandbox: valida no contexto do usuário (escopo da
 * chamada/conta) e aplica como o webhook faria (ator de sistema, transação própria).
 */
@Service
class TelephonySandboxService implements TelephonySandboxUseCases {

    private final TelephonyProperties properties;
    private final CallRepository calls;
    private final TelephonyAccountRepository accounts;
    private final ProviderEventUseCases events;
    private final CallAccess callAccess;
    private final AccessScope accessScope;
    private final TenantExecution tenantExecution;
    private final CurrentActor currentActor;
    private final Clock clock;

    TelephonySandboxService(TelephonyProperties properties, CallRepository calls, TelephonyAccountRepository accounts,
                            ProviderEventUseCases events, CallAccess callAccess, AccessScope accessScope,
                            TenantExecution tenantExecution, CurrentActor currentActor, Clock clock) {
        this.properties = properties;
        this.calls = calls;
        this.accounts = accounts;
        this.events = events;
        this.callAccess = callAccess;
        this.accessScope = accessScope;
        this.tenantExecution = tenantExecution;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @Override
    public String simulateCallEvent(UUID callId, String type, String amd, String hangupBy) {
        requireEnabled();
        Type eventType = TelephonyEnums.parse(Type.class, type, "sandbox.event_type_invalid", "Tipo do evento");
        Guard.require(eventType != Type.INBOUND, "sandbox.event_type_invalid", "Use a simulação de chamada recebida");
        Call call = tenantExecution.callAs(currentActor.get(), () -> {
            Call found = calls.findById(callId).orElseThrow(() -> ResourceNotFoundException.of("call", callId));
            callAccess.requireVisible(found);
            return found;
        });
        Guard.requireState(call.getProvider() == ProviderType.SANDBOX, "sandbox.account_not_sandbox",
                "A chamada não usa o provedor simulado");
        String recordingRef = eventType == Type.RECORDING_AVAILABLE ? "sbx-rec-" + call.getId() : null;
        ProviderCallEvent event = new ProviderCallEvent("sbx-evt-" + UUID.randomUUID(), eventType,
                call.getProviderCallId(), call.getId(), Instant.now(clock), amd, hangupBy, null, null, recordingRef,
                eventType == Type.RECORDING_AVAILABLE ? call.getTalkSeconds() : null);
        return tenantExecution.callInTenant(currentActor.tenantId(), "telephony.sandbox_event",
                () -> events.apply(call.getAccountId(), event));
    }

    @Override
    public UUID simulateInbound(UUID accountId, String from) {
        requireEnabled();
        Guard.require(from != null && !from.isBlank() && from.length() <= 32, "sandbox.from_required",
                "Número de origem obrigatório");
        TelephonyAccount account = tenantExecution.callAs(currentActor.get(), () -> accounts.findById(accountId)
                .orElseThrow(() -> ResourceNotFoundException.of("telephony_account", accountId)));
        if (account.getCreditorId() == null) {
            accessScope.requireAllCreditors("telephony_account");
        } else {
            accessScope.requireCreditor(account.getCreditorId());
        }
        Guard.requireState(account.getProvider() == ProviderType.SANDBOX, "sandbox.account_not_sandbox",
                "A conta não usa o provedor simulado");
        String providerCallId = "sbx-in-" + UUID.randomUUID();
        ProviderCallEvent event = new ProviderCallEvent("sbx-evt-" + UUID.randomUUID(), Type.INBOUND, providerCallId, null,
                Instant.now(clock), null, null, null, from.trim(), null, null);
        tenantExecution.callInTenant(currentActor.tenantId(), "telephony.sandbox_inbound",
                () -> events.apply(accountId, event));
        return tenantExecution.callInTenant(currentActor.tenantId(), "telephony.sandbox_inbound",
                () -> calls.findByProviderAndProviderCallId(ProviderType.SANDBOX, providerCallId).map(Call::getId)
                        .orElseThrow());
    }

    private void requireEnabled() {
        if (!properties.sandbox().enabled()) {
            throw new ConflictException("sandbox.disabled", "Simulação disponível apenas em ambiente local ou de teste");
        }
    }
}
