package br.com.gestorcobranca.telephony.application.port.in;

import java.util.UUID;

/**
 * Simulação do provedor para ambiente local e testes (provedor {@code SANDBOX}). Disponível apenas
 * com {@code app.telephony.sandbox.enabled=true}, recusado no perfil {@code prod}. Os eventos
 * seguem o mesmo caminho dos webhooks do provedor.
 */
public interface TelephonySandboxUseCases {

    /**
     * @param type RINGING, ANSWERED, COMPLETED, NO_ANSWER, BUSY, FAILED, CANCELLED ou RECORDING_AVAILABLE
     * @param amd  HUMAN, MACHINE ou UNKNOWN (no atendimento)
     * @param hangupBy DEBTOR, OPERATOR, SYSTEM ou PROVIDER (no encerramento)
     * @return resultado da aplicação ({@code applied}, {@code stale}…)
     */
    String simulateCallEvent(UUID callId, String type, String amd, String hangupBy);

    /** Simula uma chamada recebida do número na conta sandbox; devolve o id da chamada. */
    UUID simulateInbound(UUID accountId, String from);
}
