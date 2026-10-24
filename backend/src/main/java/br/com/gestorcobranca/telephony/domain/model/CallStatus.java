package br.com.gestorcobranca.telephony.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Ciclo de vida de uma chamada (F20). Estados terminais: {@code COMPLETED}, {@code NO_ANSWER},
 * {@code BUSY}, {@code FAILED} e {@code CANCELLED}. Só avança: eventos do provedor fora de ordem
 * (ex.: {@code RINGING} depois de {@code ANSWERED}) são descartados como obsoletos.
 *
 * <pre>
 * QUEUED ──► DIALING ──► RINGING ──► ANSWERED ──► COMPLETED
 *   │           │           │
 *   └───────────┴───────────┴──► NO_ANSWER | BUSY | FAILED | CANCELLED
 * </pre>
 * Chamadas recebidas nascem em {@code RINGING}.
 */
public enum CallStatus {
    /** Registrada, aguardando a discagem (job). */
    QUEUED,
    /** Pedido aceito pelo provedor; discando. */
    DIALING,
    /** Tocando no destino. */
    RINGING,
    /** Atendida (pessoa ou secretária eletrônica, ver AMD). */
    ANSWERED,
    /** Encerrada depois de atendida. */
    COMPLETED,
    /** Não atendida até o tempo limite de toque. */
    NO_ANSWER,
    /** Destino ocupado. */
    BUSY,
    /** Falha (número inválido, recusa do provedor, porteiro de contato, erro de rede). */
    FAILED,
    /** Cancelada antes do atendimento (operador, sistema ou campanha pausada). */
    CANCELLED;

    private static final Set<CallStatus> TERMINAL = EnumSet.of(COMPLETED, NO_ANSWER, BUSY, FAILED, CANCELLED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** Chamada em andamento (ocupa linha do provedor). */
    public boolean isActive() {
        return !isTerminal();
    }

    public boolean canTransitionTo(CallStatus target) {
        return switch (this) {
            case QUEUED -> target == DIALING || target == FAILED || target == CANCELLED;
            case DIALING -> target == RINGING || target == ANSWERED || target == NO_ANSWER || target == BUSY
                    || target == FAILED || target == CANCELLED;
            case RINGING -> target == ANSWERED || target == NO_ANSWER || target == BUSY || target == FAILED
                    || target == CANCELLED;
            case ANSWERED -> target == COMPLETED;
            case COMPLETED, NO_ANSWER, BUSY, FAILED, CANCELLED -> false;
        };
    }

    /** Ordem de progresso: evento com estado anterior ao atual é obsoleto. */
    public int rank() {
        return switch (this) {
            case QUEUED -> 0;
            case DIALING -> 1;
            case RINGING -> 2;
            case ANSWERED -> 3;
            default -> 4;
        };
    }
}
