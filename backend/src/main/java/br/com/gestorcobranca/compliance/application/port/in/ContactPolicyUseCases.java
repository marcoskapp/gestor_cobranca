package br.com.gestorcobranca.compliance.application.port.in;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Políticas de contato versionadas (RB02): padrão da assessoria e, opcionalmente, por credor
 * (prevalece canal a canal). Publicar cria nova versão imutável com vigência a partir de
 * {@code effectiveFrom}; o histórico é preservado. Publicar a política da assessoria exige
 * acesso a todos os credores; a de um credor, o credor no escopo.
 */
public interface ContactPolicyUseCases {

    PolicyView publish(PublishPolicyCommand command);

    /** Regras efetivas agora por canal, indicando a origem (credor, assessoria ou janela padrão). */
    EffectivePolicyView effective(UUID creditorId);

    /** Versões publicadas (credor nulo = assessoria), da mais recente para a mais antiga. */
    List<PolicyView> history(UUID creditorId);

    /**
     * @param creditorId    nulo = política padrão da assessoria
     * @param effectiveFrom início da vigência (nulo = agora; não pode estar no passado)
     */
    record PublishPolicyCommand(UUID creditorId, Instant effectiveFrom, String notes, List<ChannelRuleInput> rules) {

        public PublishPolicyCommand {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    /**
     * @param channel            WHATSAPP, EMAIL, SMS, PHONE ou PORTAL
     * @param windowStart        início da janela no fuso de operação (com fim e dias; nulos = janela da assessoria)
     * @param windowEnd          fim da janela (igual ao início = dia inteiro; menor = atravessa a meia-noite)
     * @param allowedDays        dias da semana (MONDAY…SUNDAY)
     * @param maxPerDay          tentativas por devedor por dia (opcional)
     * @param maxPerWeek         tentativas por devedor por semana civil (opcional)
     * @param minIntervalMinutes intervalo mínimo entre tentativas (opcional)
     * @param blockOnHolidays    bloqueia nos feriados cadastrados
     */
    record ChannelRuleInput(String channel, LocalTime windowStart, LocalTime windowEnd, List<String> allowedDays,
                            Integer maxPerDay, Integer maxPerWeek, Integer minIntervalMinutes, boolean blockOnHolidays) {
    }

    record PolicyView(UUID id, UUID creditorId, int version, Instant effectiveFrom, String notes,
                      List<ChannelRuleView> rules, Instant publishedAt, UUID publishedBy) {
    }

    /** @param inheritsWindow janela herdada da assessoria (campos de janela nulos) */
    record ChannelRuleView(String channel, boolean inheritsWindow, LocalTime windowStart, LocalTime windowEnd,
                           List<String> allowedDays, Integer maxPerDay, Integer maxPerWeek, Integer minIntervalMinutes,
                           boolean blockOnHolidays) {
    }

    /**
     * @param tenantPolicyVersion   versão vigente da política da assessoria (nula se nenhuma)
     * @param creditorPolicyVersion versão vigente da política do credor (nula se nenhuma)
     */
    record EffectivePolicyView(UUID creditorId, Integer tenantPolicyVersion, Integer creditorPolicyVersion,
                               List<EffectiveChannelRule> channels) {
    }

    /**
     * @param ruleSource  CREDITOR, TENANT ou NONE (canal sem regra: só a janela padrão)
     * @param windowSource RULE ou TENANT_DEFAULT
     */
    record EffectiveChannelRule(String channel, String ruleSource, String windowSource, LocalTime windowStart,
                                LocalTime windowEnd, List<String> allowedDays, Integer maxPerDay, Integer maxPerWeek,
                                Integer minIntervalMinutes, boolean blockOnHolidays) {
    }
}
