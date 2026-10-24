package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.negotiation.application.port.out.CaseEligibilityPort;
import br.com.gestorcobranca.negotiation.application.port.out.CaseEligibilityPort.EligibilityResult;
import br.com.gestorcobranca.negotiation.application.port.out.MonetaryIndexProvider;
import br.com.gestorcobranca.negotiation.application.service.AuthorityResolver.Resolution;
import br.com.gestorcobranca.negotiation.application.service.PolicyResolver.ResolvedPolicy;
import br.com.gestorcobranca.negotiation.domain.model.authority.AuthorityLimits;
import br.com.gestorcobranca.negotiation.domain.model.calculation.DebtComposition;
import br.com.gestorcobranca.negotiation.domain.model.calculation.IndexSeries;
import br.com.gestorcobranca.negotiation.domain.model.calculation.TitleSnapshot;
import br.com.gestorcobranca.negotiation.domain.model.offer.PaymentPlan;
import br.com.gestorcobranca.negotiation.domain.model.offer.PlanRequest;
import br.com.gestorcobranca.negotiation.domain.model.offer.PricedDebt;
import br.com.gestorcobranca.negotiation.domain.model.offer.Violation;
import br.com.gestorcobranca.negotiation.domain.model.offer.ViolationKind;
import br.com.gestorcobranca.negotiation.domain.model.policy.ComponentRates;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyTerms;
import br.com.gestorcobranca.negotiation.domain.service.DebtCalculator;
import br.com.gestorcobranca.negotiation.domain.service.OfferCalculator;
import br.com.gestorcobranca.negotiation.domain.service.TermsEvaluator;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Validação única das condições de negociação (§5): <strong>nenhum caminho</strong> — tela, API,
 * automação ou assistente — oferece ou aceita condição sem passar por aqui. Aplica, nesta ordem:
 * <ol>
 *   <li>escopo de credor do ator (403 e log de acesso) e, para o devedor no portal, somente o próprio
 *       devedor (404 e log de acesso — nunca negocia dívida de outra pessoa);</li>
 *   <li>política vigente do credor (e, se informada, que a versão esperada é a vigente);</li>
 *   <li>títulos do devedor no credor, em aberto e elegíveis ({@link CaseEligibilityPort});</li>
 *   <li>composição pelo {@link DebtCalculator} com as regras da sobreposição aplicável;</li>
 *   <li>perfil de alçada do ator ({@link AuthorityResolver}) e limites efetivos
 *       mínimo(envelope, perfil) pelo {@link TermsEvaluator}.</li>
 * </ol>
 * A simulação usa {@link #prepare} e {@link #evaluate} para gerar as ofertas; a validação de
 * condições específicas e as propostas ({@code ProposalEvaluator}) usam {@link #validate}.
 */
@Component
class NegotiationGuard {

    /** Resultado de uma avaliação. */
    enum Outcome { WITHIN_AUTHORITY, REQUIRES_APPROVAL, REJECTED }

    /**
     * @param titleIds         títulos pedidos (vazio = todos em aberto do devedor no credor)
     * @param referenceDate    data-base (nula = hoje)
     * @param expectedPolicyId versão que o chamador espera vigente (nula = não verificar)
     * @param frozenReference  revalidação de proposta já registrada (aprovação, aceite, formalização): a
     *                         data-base original vale mesmo que já tenha passado — o valor proposto não
     *                         muda com o tempo de análise; a validade da proposta é controlada por ela
     */
    record Request(UUID creditorId, UUID debtorId, List<UUID> titleIds, LocalDate referenceDate, UUID expectedPolicyId,
                   boolean frozenReference) {

        Request(UUID creditorId, UUID debtorId, List<UUID> titleIds, LocalDate referenceDate, UUID expectedPolicyId) {
            this(creditorId, debtorId, titleIds, referenceDate, expectedPolicyId, false);
        }
    }

    /**
     * Contexto calculado de uma negociação.
     *
     * @param excluded           títulos pedidos e inelegíveis, com motivos
     * @param contextViolations  violações que não dependem das condições (versão de política, elegibilidade)
     */
    record Context(Actor actor, ResolvedPolicy policy, PolicyTerms.Resolved resolved, UUID portfolioId,
                   DebtComposition composition, Resolution authority, LocalDate today,
                   Map<UUID, List<String>> excluded, List<Violation> contextViolations) {

        PolicyRules rules() {
            return resolved.rules();
        }

        AuthorityLimits limits() {
            return authority.profile().limits();
        }
    }

    record Evaluation(int level, PricedDebt priced, PaymentPlan plan, List<Violation> violations, Outcome outcome) {
    }

    private final AccessScope accessScope;
    private final AccessLogger accessLogger;
    private final PolicyResolver policies;
    private final DebtTitles debtTitles;
    private final CaseEligibilityPort eligibility;
    private final MonetaryIndexProvider indexProvider;
    private final AuthorityResolver authorityResolver;
    private final NegotiationMetrics metrics;
    private final Clock clock;
    private final DebtCalculator calculator = new DebtCalculator();
    private final OfferCalculator offers = new OfferCalculator();
    private final TermsEvaluator evaluator = new TermsEvaluator();

    NegotiationGuard(AccessScope accessScope, AccessLogger accessLogger, PolicyResolver policies, DebtTitles debtTitles,
                     CaseEligibilityPort eligibility, MonetaryIndexProvider indexProvider,
                     AuthorityResolver authorityResolver, NegotiationMetrics metrics, Clock clock) {
        this.accessScope = accessScope;
        this.accessLogger = accessLogger;
        this.policies = policies;
        this.debtTitles = debtTitles;
        this.eligibility = eligibility;
        this.indexProvider = indexProvider;
        this.authorityResolver = authorityResolver;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Monta o contexto. Títulos inelegíveis ficam fora da composição e são listados em
     * {@link Context#excluded()}; sem nenhum título elegível, 422.
     *
     * @param strict validação de condições (proposta): títulos pedidos e inelegíveis também viram
     *               violações de elegibilidade, que reprovam as condições. Na simulação ({@code false})
     *               eles apenas ficam de fora.
     */
    Context prepare(Actor actor, Request request, boolean strict) {
        Guard.require(request.creditorId() != null && request.debtorId() != null, "negotiation.subject_required",
                "Credor e devedor obrigatórios");
        accessScope.requireCreditor(request.creditorId());
        DebtorSelfScope.require(actor, request.debtorId(), accessLogger);
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        LocalDate reference = request.referenceDate() == null ? today : request.referenceDate();
        ResolvedPolicy policy = policies.effective(request.creditorId(), today)
                .orElseThrow(() -> new BusinessRuleException("negotiation.policy_missing",
                        "O credor não tem política de negociação vigente"));
        List<Violation> contextViolations = new ArrayList<>();
        if (request.expectedPolicyId() != null && !request.expectedPolicyId().equals(policy.id())) {
            contextViolations.add(Violation.of(ViolationKind.POLICY, "policy.version_not_effective",
                    "A versão de política informada não é a vigente (vigente: v" + policy.policy().getPolicyVersion() + ")"));
        }
        Guard.require(request.frozenReference() || !reference.isBefore(today), "negotiation.reference_date_past",
                "A data-base não pode ser anterior a hoje");

        Map<UUID, TitleView> debtorTitles = debtTitles.ofDebtor(request.debtorId(), request.creditorId());
        List<UUID> requested = request.titleIds().isEmpty() ? debtTitles.openIds(debtorTitles)
                : List.copyOf(new LinkedHashSet<>(request.titleIds()));
        Guard.require(!requested.isEmpty(), "negotiation.no_titles", "O devedor não tem títulos em aberto neste credor");
        Guard.require(requested.size() <= 200, "negotiation.too_many_titles", "No máximo 200 títulos por negociação");
        EligibilityResult result = eligibility.check(request.creditorId(), request.debtorId(), requested);
        if (strict) {
            result.ineligible().forEach((titleId, reasons) -> reasons.forEach(reason -> contextViolations.add(
                    new Violation(ViolationKind.ELIGIBILITY, reason, "Título inelegível para negociação", null, titleId))));
        }
        List<UUID> eligible = requested.stream().filter(result.eligible()::contains).toList();
        Guard.require(!eligible.isEmpty(), "negotiation.no_eligible_titles", "Nenhum título elegível para negociação");

        List<TitleSnapshot> snapshots = debtTitles.snapshots(eligible, debtorTitles);
        Set<UUID> portfolios = new LinkedHashSet<>();
        snapshots.forEach(s -> portfolios.add(s.portfolioId()));
        UUID portfolioId = portfolios.size() == 1 ? portfolios.iterator().next() : null;
        long maxDays = snapshots.stream().mapToLong(s -> Math.max(0, ChronoUnit.DAYS.between(s.dueDate(), reference)))
                .max().orElse(0);
        PolicyTerms.Resolved resolved = policy.terms().resolve(portfolioId, maxDays);
        PolicyRules rules = resolved.rules();
        Guard.require(!reference.isAfter(today.plusDays(rules.validity().maxDaysToFirstPayment())),
                "negotiation.reference_date_out_of_range",
                "Data-base até " + rules.validity().maxDaysToFirstPayment() + " dias a partir de hoje");
        IndexSeries index = null;
        String indexCode = rules.charges().correctionIndexCode();
        if (indexCode != null) {
            index = indexProvider.series(indexCode).orElseThrow(() -> new BusinessRuleException(
                    "negotiation.index_unavailable", "Índice de correção " + indexCode + " indisponível"));
        }
        DebtComposition composition = calculator.calculate(snapshots, rules.charges(), reference, index);
        if (indexCode != null && !composition.notes().isEmpty()) {
            metrics.indexPending(indexCode);
        }
        Resolution authority = authorityResolver.resolve(actor, request.creditorId());
        return new Context(actor, policy, resolved, portfolioId, composition, authority, today, result.ineligible(),
                List.copyOf(contextViolations));
    }

    /** Aplica descontos e forma de pagamento ao contexto e avalia contra política e alçada. */
    Evaluation evaluate(Context ctx, int level, boolean justified, ComponentRates discounts, PlanRequest planRequest) {
        PricedDebt priced = offers.price(ctx.composition(), ctx.rules().charges(), discounts);
        Money negotiated = priced.net().total();
        Money minimumDown = TermsEvaluator.minimumDownPayment(negotiated, ctx.rules(), ctx.limits());
        PaymentPlan plan = offers.plan(negotiated, planRequest, ctx.rules().installments(), minimumDown);
        List<Violation> violations = new ArrayList<>(ctx.contextViolations());
        violations.addAll(evaluator.evaluate(new TermsEvaluator.Context(ctx.rules(), ctx.limits(), ctx.today(),
                ctx.composition().referenceDate()), new TermsEvaluator.Terms(level, justified, discounts, plan)));
        return new Evaluation(level, priced, plan, List.copyOf(violations), outcome(violations));
    }

    /** Valida condições específicas: contexto + avaliação, com métricas da decisão. */
    Evaluation validate(Context ctx, int level, boolean justified, ComponentRates discounts, PlanRequest planRequest) {
        Evaluation evaluation = evaluate(ctx, level, justified, discounts, planRequest);
        String actorType = ctx.actor().type().name();
        metrics.guardDecision(actorType, evaluation.outcome().name());
        evaluation.violations().forEach(v -> metrics.guardViolation(v.code()));
        return evaluation;
    }

    static Outcome outcome(List<Violation> violations) {
        if (violations.stream().anyMatch(v -> v.kind() != ViolationKind.AUTHORITY)) {
            return Outcome.REJECTED;
        }
        return violations.isEmpty() ? Outcome.WITHIN_AUTHORITY : Outcome.REQUIRES_APPROVAL;
    }
}
