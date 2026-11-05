package br.com.gestorcobranca.collection.application.port.in;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Configuração das contestações pela assessoria ({@code DISPUTE_MANAGE}; consulta também com {@code DISPUTE_WORK}):
 * parâmetros (distribuição, prazo interno padrão, prazo pedido ao credor, lembrete, validade do link), prazos por
 * tipo e por credor, analistas da distribuição automática e modelos de e-mail versionados. Itens da assessoria
 * (sem credor) exigem acesso a todos os credores; itens de um credor, acesso a ele. Toda alteração é auditada.
 */
public interface DisputeConfigUseCases {

    DisputeSettingsView settings();

    DisputeSettingsView updateSettings(DisputeSettingsCommand command);

    List<DeadlineRuleView> rules();

    DeadlineRuleView createRule(DeadlineRuleCommand command);

    DeadlineRuleView updateRule(UUID ruleId, int responseDays, int internalDays);

    void removeRule(UUID ruleId);

    /** Prazos que uma contestação do tipo no credor receberia se aberta hoje (e de qual regra vieram). */
    DeadlinePreview previewDeadlines(UUID creditorId, String type);

    List<PoolMemberView> pool();

    PoolMemberView addPoolMember(UUID creditorId, UUID userId);

    void removePoolMember(UUID memberId);

    /** Modelos vigentes de todos os tipos (pedido ao credor) e resultados (resposta ao devedor). */
    List<TemplateView> templates();

    /** Versões gravadas de um modelo, da mais recente para a mais antiga (vazio = só o padrão do sistema). */
    List<TemplateView> templateHistory(String kind, String key);

    /** Grava uma nova versão do modelo (as anteriores ficam no histórico). */
    TemplateView saveTemplate(String kind, String key, String subject, String body);

    /** Nova versão com o texto padrão do sistema. */
    TemplateView restoreDefaultTemplate(String kind, String key);

    /**
     * @param responseDays prazo padrão de resposta ao devedor (parâmetro da operação, {@code disputeResponseDays})
     * @param customized   a assessoria já gravou os parâmetros de contestação
     */
    record DisputeSettingsView(String distributionMode, int internalDays, int creditorRequestDays,
                               int reminderDaysBefore, int evidenceLinkDays, int responseDays, boolean customized,
                               Instant updatedAt) {
    }

    /** @param distributionMode MANUAL ou LEAST_LOADED */
    record DisputeSettingsCommand(String distributionMode, int internalDays, int creditorRequestDays,
                                  int reminderDaysBefore, int evidenceLinkDays) {
    }

    /** @param creditorId nulo = todos os credores; @param type nulo = todos os tipos (exige credor) */
    record DeadlineRuleCommand(UUID creditorId, String type, int responseDays, int internalDays) {
    }

    record DeadlineRuleView(UUID id, UUID creditorId, String type, int responseDays, int internalDays, String source,
                            Instant updatedAt, long version) {
    }

    record DeadlinePreview(LocalDate responseDueDate, LocalDate internalDueDate, int responseDays, int internalDays,
                           String source) {
    }

    record PoolMemberView(UUID id, UUID creditorId, UUID userId, String userName, boolean eligible, long openDisputes) {
    }

    /**
     * @param kind      CREDITOR_REQUEST (chave = tipo da contestação) ou DEBTOR_RESPONSE (chave = resultado)
     * @param version   0 = padrão do sistema (sem versão gravada)
     * @param variables variáveis aceitas no modelo
     */
    record TemplateView(String kind, String key, int version, String subject, String body, boolean customized,
                        List<String> variables, Instant createdAt, UUID createdBy) {
    }
}
