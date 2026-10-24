package br.com.gestorcobranca.telephony.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Operadores na telefonia e presença para o discador (ACD). O gestor habilita o operador e define
 * o ramal ({@code TELEPHONY_MANAGE}); o operador entra (opcionalmente numa campanha), fica
 * disponível, pausa com motivo e sai; {@code ON_CALL} e {@code WRAP_UP} são conduzidos pelas
 * chamadas. O cliente (estação de cobrança) envia {@link #heartbeat()} periodicamente: sem sinal
 * além do limite, o operador sai automaticamente. Cada mudança grava um intervalo de presença
 * (append-only) e o evento {@code agent.presence_changed}.
 */
public interface AgentUseCases {

    /** Habilita ou altera o operador na telefonia (ramal/usuário SIP). */
    AgentView configure(UUID userId, ConfigureAgentCommand command);

    /** Todos os operadores habilitados com a presença atual (supervisão). */
    List<AgentView> list();

    AgentView me();

    AgentView login(UUID campaignId);

    AgentView available();

    /** @param reason BREAK, LUNCH, RESTROOM, TRAINING, FEEDBACK, MEETING, BACKOFFICE, TECHNICAL_ISSUE ou OTHER */
    AgentView pause(String reason);

    /** Entra (ou sai, com nulo) de uma campanha de discagem ativa. */
    AgentView joinCampaign(UUID campaignId);

    AgentView logout();

    void heartbeat();

    /** Saída forçada pela supervisão (motivo obrigatório, auditada). */
    AgentView forceLogout(UUID userId, String reason);

    record ConfigureAgentCommand(String extension, boolean active) {
    }

    /**
     * @param status OFFLINE, AVAILABLE, ON_CALL, WRAP_UP ou PAUSED
     */
    record AgentView(UUID id, UUID userId, String extension, boolean active, String status, String pauseReason,
                     UUID campaignId, UUID currentCallId, Instant statusSince, Instant lastAvailableAt,
                     Instant lastHeartbeatAt, Instant loggedInAt, long version) {
    }
}
