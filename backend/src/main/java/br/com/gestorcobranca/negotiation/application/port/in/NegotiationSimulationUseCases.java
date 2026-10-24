package br.com.gestorcobranca.negotiation.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Simulação e validação de condições (§2–§5), com {@code NEGOTIATION_SIMULATE} e o credor no escopo
 * do ator. Humanos e o assistente usam exatamente estes casos de uso: as ofertas devolvidas e a
 * validação passam pelo {@code NegotiationGuard} com o perfil de alçada do ator.
 */
public interface NegotiationSimulationUseCases {

    /**
     * Calcula a composição dos títulos na data e devolve ofertas até o nível permitido ao ator,
     * para as formas de pagamento pedidas (padrão: à vista e o maior parcelamento permitido).
     * Registra a simulação com a memória de cálculo e o evento de negócio {@code negotiation.simulated}.
     */
    SimulationView simulate(SimulateCommand command);

    SimulationView get(UUID simulationId);

    /**
     * Valida condições específicas (nível, descontos e forma de pagamento) como fará a proposta:
     * política vigente, envelope, alçada do ator, escopo de credor e elegibilidade dos títulos.
     * Não grava nada além de métricas.
     */
    ValidationView validate(ValidateCommand command);

    /**
     * @param titleIds       títulos a negociar (vazio = todos os títulos em aberto do devedor no credor)
     * @param referenceDate  data-base do cálculo (nula = hoje; até o prazo do 1º pagamento da política)
     * @param requestedLevel nível da escada pedido (nulo = 0); acima de 0 exige {@code justification}
     * @param plans          formas de pagamento (vazio = à vista e o maior parcelamento permitido)
     * @param channel        canal do atendimento (PHONE, WHATSAPP, EMAIL, SMS, PORTAL, IN_PERSON — opcional)
     */
    record SimulateCommand(UUID creditorId, UUID debtorId, List<UUID> titleIds, LocalDate referenceDate,
                           Integer requestedLevel, JustificationInput justification, List<PlanInput> plans,
                           String channel) {

        public SimulateCommand {
            titleIds = titleIds == null ? List.of() : List.copyOf(titleIds);
            plans = plans == null ? List.of() : List.copyOf(plans);
        }
    }

    /** @param reason código de {@code ConcessionReason}; {@code note} obrigatória para OTHER */
    record JustificationInput(String reason, String note) {
    }

    /**
     * @param installments número de pagamentos incluindo a entrada (1 = à vista)
     * @param downPayment  entrada (opcional)
     * @param firstDueDate 1º vencimento (nulo = data-base)
     */
    record PlanInput(int installments, BigDecimal downPayment, LocalDate firstDueDate) {
    }

    /**
     * @param policyId versão de política que o chamador espera vigente (nula = a vigente)
     * @param discounts desconto por componente (%); nulos = 0
     */
    record ValidateCommand(UUID creditorId, UUID debtorId, List<UUID> titleIds, LocalDate referenceDate, UUID policyId,
                           int level, JustificationInput justification, ComponentsView discounts, PlanInput plan,
                           String channel) {

        public ValidateCommand {
            titleIds = titleIds == null ? List.of() : List.copyOf(titleIds);
        }
    }

    /** Valores por componente (reais ou percentuais, conforme o campo que os contém). */
    record ComponentsView(BigDecimal principal, BigDecimal correction, BigDecimal interest, BigDecimal fine,
                          BigDecimal fees, BigDecimal honorarium) {
    }

    record SimulationView(UUID id, Instant createdAt, SimulationResult result) {
    }

    /**
     * Resultado completo de uma simulação (persistido como memória de cálculo).
     *
     * @param grantedLevel      maior nível ofertado (mínimo entre o pedido, a alçada e o topo da escada)
     * @param levelCapped       o nível pedido excede a alçada do ator
     * @param nextLevelAvailable existe nível acima do concedido dentro da alçada (exige justificativa)
     * @param envelope          envelope completo da política — somente para quem pode usar a escada inteira;
     *                          nunca para o assistente (minimização: o modelo só vê ofertas)
     * @param excludedTitles    títulos pedidos que ficaram fora por inelegibilidade
     * @param breakRule         regra de quebra que valerá para o acordo (exibida antes do aceite; nula em
     *                          simulações anteriores a este campo)
     * @param proposalValidityDays validade da proposta em dias (idem)
     */
    record SimulationResult(UUID creditorId, UUID debtorId, UUID portfolioId, PolicyRef policy, LocalDate referenceDate,
                            ActorRef actor, LimitsView limits, int requestedLevel, int grantedLevel, boolean levelCapped,
                            boolean nextLevelAvailable, JustificationInput justification, String channel,
                            CompositionView composition, List<EnvelopeTierView> envelope, List<OfferView> offers,
                            List<ExcludedTitleView> excludedTitles, List<String> notes, int engineVersion,
                            AgreementUseCases.BreakRuleView breakRule, Integer proposalValidityDays,
                            Integer maxDaysToFirstPayment) {
    }

    record PolicyRef(UUID policyId, int version, String overrideCode) {
    }

    record ActorRef(String type, UUID id, UUID profileId, String profileCode) {
    }

    /** Limites efetivos: mínimo(política, perfil). */
    record LimitsView(int maxLevel, BigDecimal maxDiscountShare, int maxInstallments, BigDecimal maxAgreementAmount,
                      BigDecimal minDownPaymentShare, boolean canFormalize, boolean canApprove) {
    }

    record CompositionView(LocalDate referenceDate, List<TitleCompositionView> titles, ComponentsView total,
                           BigDecimal totalAmount, BigDecimal paidTotal, long maxDaysOverdue) {
    }

    record TitleCompositionView(UUID titleId, UUID portfolioId, LocalDate dueDate, long daysOverdue,
                                ComponentsView components, BigDecimal total, BigDecimal paidTotal,
                                BigDecimal correctionFactor, List<CalculationLineView> lines) {
    }

    record CalculationLineView(UUID titleId, String component, String rule, BigDecimal base, BigDecimal rate, Long days,
                               BigDecimal factor, BigDecimal amount, String note) {
    }

    /** Desconto máximo da política para a faixa e valor mínimo resultante. */
    record EnvelopeTierView(int upToInstallments, ComponentsView maxDiscountRates, BigDecimal maxDiscountAmount,
                            BigDecimal minimumNegotiated) {
    }

    /**
     * @param valid           sem violação de política
     * @param withinAuthority sem violação de alçada (pode ser proposta sem aprovação)
     */
    record OfferView(int level, String levelName, int installments, boolean valid, boolean withinAuthority,
                     ComponentsView discountRates, ComponentsView discount, BigDecimal discountTotal,
                     ComponentsView net, BigDecimal negotiated, BigDecimal downPayment, BigDecimal total,
                     BigDecimal financingInterest, BigDecimal periodRate, List<PaymentView> schedule,
                     List<CalculationLineView> discountLines, List<ViolationView> violations) {
    }

    record PaymentView(int number, LocalDate dueDate, BigDecimal amount, BigDecimal interest, BigDecimal amortization,
                       BigDecimal balanceAfter) {
    }

    record ViolationView(String kind, String code, String message, String component, UUID titleId) {
    }

    record ExcludedTitleView(UUID titleId, List<String> reasons) {
    }

    /**
     * @param outcome WITHIN_AUTHORITY (o ator pode propor/formalizar), REQUIRES_APPROVAL (dentro da
     *                política, acima da alçada) ou REJECTED (fora da política ou inelegível)
     */
    record ValidationView(String outcome, PolicyRef policy, ActorRef actor, LimitsView limits, OfferView offer,
                          List<ViolationView> violations, List<ExcludedTitleView> excludedTitles) {
    }
}
