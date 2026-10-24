package br.com.gestorcobranca.telephony.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Campanhas de discagem (F20): lista de casos a partir de uma fila do collection (port público,
 * casos elegíveis em OPEN/IN_NEGOTIATION, aguardando na fila salvo {@code includeAssigned}),
 * ritmo progressivo ou preditivo com abandono máximo (padrão 3%), retentativas com intervalo,
 * janela de horário própria e distribuição da chamada atendida ao operador livre há mais tempo.
 * Toda discagem passa por {@code ContactGate#authorizeAndRecord} (origem {@code CAMPAIGN}).
 * <p>
 * Escopo: campanha de um credor exige o credor no escopo; sem credor (fila de vários credores),
 * acesso a todos os credores. Configurar exige {@code TELEPHONY_MANAGE}; ativar, pausar, encerrar
 * e recarregar a lista, {@code TELEPHONY_SUPERVISE}.
 */
public interface DialerCampaignUseCases {

    CampaignView create(CreateCampaignCommand command);

    CampaignView update(UUID campaignId, CampaignSettings settings);

    CampaignView get(UUID campaignId);

    List<CampaignView> list();

    /** Ativa e monta (ou completa) a lista a partir da fila em segundo plano. */
    CampaignView activate(UUID campaignId);

    /** Para de discar; chamadas em andamento seguem até o fim. */
    CampaignView pause(UUID campaignId);

    /** Encerra: itens pendentes saem da lista; operadores deixam de receber chamadas da campanha. */
    CampaignView finish(UUID campaignId);

    /** Acrescenta à lista os casos que entraram na fila desde a última carga. */
    CampaignView reload(UUID campaignId);

    PageResponse<EntryView> entries(UUID campaignId, String status, PageQuery page);

    /** Indicadores do dia de operação (abandono, ASR, tempos) e do ritmo atual. */
    CampaignStats stats(UUID campaignId);

    record CreateCampaignCommand(String code, UUID creditorId, UUID queueId, CampaignSettings settings) {
    }

    /**
     * @param mode           PROGRESSIVE ou PREDICTIVE
     * @param maxDialRatio   chamadas por operador livre no preditivo (1 a 5)
     * @param maxAbandonRate abandono máximo em % (nulo = 3)
     * @param windowStart    janela própria no fuso de operação (opcional; o porteiro aplica a da política)
     */
    record CampaignSettings(String name, UUID portfolioId, boolean includeAssigned, String mode,
                            BigDecimal maxDialRatio, BigDecimal maxAbandonRate, Integer maxAttempts,
                            Integer retryIntervalMinutes, Integer ringTimeoutSeconds, LocalTime windowStart,
                            LocalTime windowEnd) {
    }

    record CampaignView(UUID id, String code, String name, UUID creditorId, UUID portfolioId, UUID queueId,
                        boolean includeAssigned, String mode, String status, BigDecimal maxDialRatio,
                        BigDecimal maxAbandonRate, int maxAttempts, int retryIntervalMinutes, int ringTimeoutSeconds,
                        LocalTime windowStart, LocalTime windowEnd, Instant activatedAt, Instant pausedAt,
                        Instant finishedAt, Instant lastLoadedAt, int entriesLoaded, long version) {
    }

    /** @param status PENDING, IN_PROGRESS, COMPLETED, EXHAUSTED ou EXCLUDED */
    record EntryView(UUID id, UUID caseId, UUID debtorId, UUID creditorId, UUID portfolioId, String status,
                     int priority, int attempts, Instant nextAttemptAt, UUID lastCallId, String lastResult,
                     Instant lastAttemptAt, List<String> lastDenial, String excludedReason, Instant finishedAt) {
    }

    /**
     * @param asr              atendidas ÷ discadas (%)
     * @param abandonRate      abandonadas ÷ atendidas por pessoa (%)
     * @param currentRatio     razão de discagem que o ritmo aplicaria agora
     * @param pacingReason     motivo da razão (progressive, warming_up, predictive, abandon_near_limit, abandon_limit…)
     */
    record CampaignStats(UUID campaignId, Instant since, long dialed, long answered, long live, long connected,
                         long abandoned, long machine, BigDecimal asr, BigDecimal abandonRate,
                         BigDecimal maxAbandonRate, Double averageTalkSeconds, Double averageRingSeconds,
                         Double averageWrapUpSeconds, long availableAgents, long callsInProgress,
                         BigDecimal currentRatio, String pacingReason, long pending, long inProgress, long completed,
                         long exhausted, long excluded) {
    }
}
