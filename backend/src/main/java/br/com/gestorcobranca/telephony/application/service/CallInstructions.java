package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.telephony.application.port.out.CallInstruction;
import br.com.gestorcobranca.telephony.application.port.out.CallInstruction.Announce;
import br.com.gestorcobranca.telephony.application.port.out.CallInstruction.ConnectAgent;
import br.com.gestorcobranca.telephony.application.port.out.CallInstruction.DialDebtor;
import br.com.gestorcobranca.telephony.application.port.out.CallInstruction.Hangup;
import br.com.gestorcobranca.telephony.application.port.out.CallInstruction.Hold;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyWebhookAdapter.InstructionRequest;
import br.com.gestorcobranca.telephony.domain.model.Agent;
import br.com.gestorcobranca.telephony.domain.model.Call;
import br.com.gestorcobranca.telephony.domain.model.CallOrigin;
import br.com.gestorcobranca.telephony.domain.model.TelephonyAccount;
import br.com.gestorcobranca.telephony.domain.repository.AgentRepository;
import br.com.gestorcobranca.telephony.domain.repository.CallRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Instruções da chamada para provedores que as pedem por webhook (TwiML da Twilio, XML da Plivo),
 * derivadas só do estado da chamada já gravado (o evento do mesmo webhook é aplicado antes):
 * <ul>
 *   <li>operador atendeu no clique para ligar → discar para o devedor (o número sai do cadastro
 *       criptografado da chamada direto para o corpo da resposta; nunca vai em URL);</li>
 *   <li>atendida pelo discador aguardando AMD/operador → aguardar na linha;</li>
 *   <li>conectada pelo domínio → ramal do operador na conta;</li>
 *   <li>encerrada sem operador (abandono, recebida perdida) → mensagem informativa e fim;</li>
 *   <li>chamada desconhecida, encerrada ou de outra conta → fim, sem mensagem.</li>
 * </ul>
 */
@Component
class CallInstructions {

    /** Causa gravada na chamada recebida encerrada sem operador. */
    static final String NO_AGENT = "NO_AGENT";
    /** Toque do devedor no clique para ligar (mesmo prazo do {@code originate}). */
    static final int CLICK_TO_CALL_RING_SECONDS = 30;

    private final CallRepository calls;
    private final AgentRepository agents;
    private final AgentEndpoints endpoints;

    CallInstructions(CallRepository calls, AgentRepository agents, AgentEndpoints endpoints) {
        this.calls = calls;
        this.agents = agents;
        this.endpoints = endpoints;
    }

    CallInstruction resolve(TelephonyAccount account, InstructionRequest request) {
        if (request == null || request.kind() == null) {
            return new Hangup(false);
        }
        switch (request.kind()) {
            case ANNOUNCE -> {
                return new Announce();
            }
            case END -> {
                return new Hangup(false);
            }
            case ABANDON -> {
                return new Hangup(true);
            }
            default -> {
                // segue
            }
        }
        Optional<Call> found = find(account, request);
        if (found.isEmpty()) {
            return new Hangup(false);
        }
        Call call = found.get();
        return switch (request.kind()) {
            case DIAL_DEBTOR -> dialDebtor(account, call);
            case CONNECT_OPERATOR -> call.getStatus().isActive()
                    ? connect(account, request.operatorId(), call.isRecordingRequested(), call.isRecordingAnnounced())
                    : new Hangup(false);
            case CALL_ANSWERED -> answered(account, call);
            default -> new Hangup(false);
        };
    }

    private CallInstruction dialDebtor(TelephonyAccount account, Call call) {
        if (call.getOrigin() != CallOrigin.CLICK_TO_CALL || !call.getStatus().isActive() || call.getNumber() == null) {
            return new Hangup(false);
        }
        return new DialDebtor(call.getNumber(), account.getCallerId(), CLICK_TO_CALL_RING_SECONDS,
                call.isRecordingRequested(), call.isRecordingAnnounced());
    }

    private CallInstruction answered(TelephonyAccount account, Call call) {
        if (!call.getStatus().isActive()) {
            return new Hangup(call.isAbandoned() || NO_AGENT.equals(call.getFailureCode()));
        }
        if (call.getConnectedAt() != null && call.getOperatorId() != null) {
            // o adaptador sabe, pelo passo da chamada, se a gravação e o aviso já começaram
            return connect(account, call.getOperatorId(), call.isRecordingRequested(), call.isRecordingAnnounced());
        }
        if (call.getOrigin() == CallOrigin.DIALER) {
            return new Hold(call.isRecordingAnnounced(), call.isRecordingRequested(), 0);
        }
        return new Hangup(false);
    }

    private CallInstruction connect(TelephonyAccount account, UUID operatorId, boolean record, boolean announce) {
        if (operatorId == null) {
            return new Hangup(true);
        }
        Optional<String> extension = agents.findByUserId(operatorId)
                .flatMap((Agent agent) -> endpoints.extensionFor(agent, account.getId()));
        return extension.<CallInstruction>map(e -> new ConnectAgent(e, account.getCallerId(), record, announce))
                .orElse(new Hangup(true));
    }

    private Optional<Call> find(TelephonyAccount account, InstructionRequest request) {
        Optional<Call> call = Optional.empty();
        if (request.providerCallId() != null && !request.providerCallId().isBlank()) {
            call = calls.findByProviderAndProviderCallId(account.getProvider(), request.providerCallId());
        }
        if (call.isEmpty() && request.callReference() != null) {
            call = calls.findById(request.callReference());
        }
        return call.filter(c -> c.getAccountId().equals(account.getId()));
    }
}
