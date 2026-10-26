package br.com.gestorcobranca.campaign.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Filtros combináveis de um segmento (todos por <b>E</b>; nulo/vazio = sem filtro). Avaliados sobre o
 * read model de audiência ({@link AudienceSnapshot}) — em SQL para contagem e seleção em lote, e em
 * memória ({@code SegmentMatcher}) para reavaliar um caso antes de cada etapa. Os dois caminhos
 * seguem exatamente a mesma semântica, descrita em {@code docs/tecnico/desenho/campanhas.md} §2.
 *
 * @param portfolioIds          carteiras (devem ser do credor do segmento, quando houver)
 * @param minDaysOverdue        atraso mínimo (dias desde o vencimento mais antigo em cobrança)
 * @param maxDaysOverdue        atraso máximo
 * @param minBalance            saldo em aberto mínimo
 * @param maxBalance            saldo em aberto máximo
 * @param caseStatuses          situações do caso (vazio = qualquer situação ativa)
 * @param eligibleOnly          só casos elegíveis (RB02); padrão {@code true}
 * @param contactableChannel    canal que precisa estar sem restrição (WHATSAPP, EMAIL, SMS, PHONE)
 * @param minInteractions       interações registradas, mínimo
 * @param maxInteractions       interações registradas, máximo (0 = nunca trabalhado)
 * @param everEffectiveContact  {@code true} = já houve contato efetivo; {@code false} = nunca houve
 * @param noInteractionDays     sem interação nos últimos N dias (inclui nunca)
 * @param noEffectiveContactDays sem contato efetivo nos últimos N dias (inclui nunca)
 * @param pendingPromise        com ({@code true}) ou sem ({@code false}) promessa pendente
 * @param activeAgreement       com ({@code true}) ou sem ({@code false}) acordo em vigor
 * @param minBrokenAgreements   acordos quebrados no caso, mínimo
 * @param minScore              score de propensão mínimo (critério opcional; casos sem score ficam fora)
 * @param maxScore              score máximo (idem)
 * @param ownerTypes            dono do atendimento (ASSISTANT, QUEUE, USER, NONE)
 * @param minDaysToInstallment  próxima parcela em aberto do acordo em vigor vence em ao menos N dias (0 = hoje);
 *                              casos sem acordo em vigor com parcela em aberto ficam fora
 * @param maxDaysToInstallment  idem, vence em até N dias (lembrete de parcela: "vence nos próximos N dias")
 */
public record SegmentCriteria(
        Set<UUID> portfolioIds,
        Integer minDaysOverdue,
        Integer maxDaysOverdue,
        BigDecimal minBalance,
        BigDecimal maxBalance,
        Set<String> caseStatuses,
        Boolean eligibleOnly,
        String contactableChannel,
        Integer minInteractions,
        Integer maxInteractions,
        Boolean everEffectiveContact,
        Integer noInteractionDays,
        Integer noEffectiveContactDays,
        Boolean pendingPromise,
        Boolean activeAgreement,
        Integer minBrokenAgreements,
        Integer minScore,
        Integer maxScore,
        Set<String> ownerTypes,
        Integer minDaysToInstallment,
        Integer maxDaysToInstallment) {

    public static final Set<String> CASE_STATUSES = Set.of("OPEN", "IN_NEGOTIATION", "PROMISE", "AGREEMENT", "DISPUTED",
            "SUSPENDED", "LEGAL");
    public static final Set<String> CHANNELS = Set.of("WHATSAPP", "EMAIL", "SMS", "PHONE");
    public static final Set<String> OWNER_TYPES = Set.of("ASSISTANT", "QUEUE", "USER", "NONE");
    private static final int MAX_DAYS = 36_500;
    /** Horizonte do filtro de parcela vencendo (um ano). */
    public static final int MAX_INSTALLMENT_DAYS = 366;

    public SegmentCriteria {
        portfolioIds = portfolioIds == null ? Set.of() : Set.copyOf(portfolioIds);
        caseStatuses = upper(caseStatuses);
        ownerTypes = upper(ownerTypes);
        eligibleOnly = eligibleOnly == null ? Boolean.TRUE : eligibleOnly;
        contactableChannel = contactableChannel == null || contactableChannel.isBlank() ? null
                : contactableChannel.trim().toUpperCase(Locale.ROOT);
    }

    /** Segmento sem filtros além da elegibilidade. */
    public static SegmentCriteria any() {
        return new SegmentCriteria(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    /** Valida faixas e valores; lança {@code BusinessRuleException} com código {@code segment.*}. */
    public SegmentCriteria validate() {
        range(minDaysOverdue, maxDaysOverdue, "segment.days_overdue_invalid", "Faixa de atraso inválida");
        Guard.require(minBalance == null || minBalance.signum() >= 0, "segment.balance_invalid", "Saldo mínimo negativo");
        Guard.require(maxBalance == null || maxBalance.signum() >= 0, "segment.balance_invalid", "Saldo máximo negativo");
        Guard.require(minBalance == null || maxBalance == null || minBalance.compareTo(maxBalance) <= 0,
                "segment.balance_invalid", "Saldo mínimo maior que o máximo");
        Guard.require(CASE_STATUSES.containsAll(caseStatuses), "segment.case_status_invalid",
                "Situações válidas: " + String.join(", ", CASE_STATUSES));
        Guard.require(contactableChannel == null || CHANNELS.contains(contactableChannel), "segment.channel_invalid",
                "Canal inválido");
        Guard.require(OWNER_TYPES.containsAll(ownerTypes), "segment.owner_type_invalid", "Dono do atendimento inválido");
        range(minInteractions, maxInteractions, "segment.interactions_invalid", "Faixa de interações inválida");
        Guard.require(minDaysToInstallment == null || (minDaysToInstallment >= 0 && minDaysToInstallment <= MAX_INSTALLMENT_DAYS),
                "segment.installment_days_invalid", "Dias até a parcela entre 0 e " + MAX_INSTALLMENT_DAYS);
        Guard.require(maxDaysToInstallment == null || (maxDaysToInstallment >= 0 && maxDaysToInstallment <= MAX_INSTALLMENT_DAYS),
                "segment.installment_days_invalid", "Dias até a parcela entre 0 e " + MAX_INSTALLMENT_DAYS);
        Guard.require(minDaysToInstallment == null || maxDaysToInstallment == null
                        || minDaysToInstallment <= maxDaysToInstallment, "segment.installment_days_invalid",
                "Dias até a parcela: mínimo maior que o máximo");
        Guard.require(!Boolean.FALSE.equals(activeAgreement) || (minDaysToInstallment == null && maxDaysToInstallment == null),
                "segment.installment_without_agreement", "Parcela vencendo exige acordo em vigor");
        nonNegative(noInteractionDays, "segment.days_invalid");
        nonNegative(noEffectiveContactDays, "segment.days_invalid");
        nonNegative(minBrokenAgreements, "segment.broken_agreements_invalid");
        Guard.require(minScore == null || (minScore >= 0 && minScore <= 1000), "segment.score_invalid", "Score entre 0 e 1000");
        Guard.require(maxScore == null || (maxScore >= 0 && maxScore <= 1000), "segment.score_invalid", "Score entre 0 e 1000");
        Guard.require(minScore == null || maxScore == null || minScore <= maxScore, "segment.score_invalid",
                "Score mínimo maior que o máximo");
        return this;
    }

    private static void range(Integer min, Integer max, String code, String message) {
        Guard.require(min == null || (min >= 0 && min <= MAX_DAYS), code, message);
        Guard.require(max == null || (max >= 0 && max <= MAX_DAYS), code, message);
        Guard.require(min == null || max == null || min <= max, code, message);
    }

    private static void nonNegative(Integer value, String code) {
        Guard.require(value == null || (value >= 0 && value <= MAX_DAYS), code, "Valor inválido");
    }

    private static Set<String> upper(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        values.stream().filter(v -> v != null && !v.isBlank()).forEach(v -> result.add(v.trim().toUpperCase(Locale.ROOT)));
        return Set.copyOf(result);
    }
}
