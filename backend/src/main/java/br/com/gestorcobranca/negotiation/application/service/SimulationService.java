package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases;
import br.com.gestorcobranca.negotiation.application.service.NegotiationGuard.Context;
import br.com.gestorcobranca.negotiation.application.service.NegotiationGuard.Evaluation;
import br.com.gestorcobranca.negotiation.domain.model.authority.AuthorityLimits;
import br.com.gestorcobranca.negotiation.domain.model.offer.PlanRequest;
import br.com.gestorcobranca.negotiation.domain.model.offer.PricedDebt;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.ConcessionLevel;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.DiscountTier;
import br.com.gestorcobranca.negotiation.domain.model.simulation.ConcessionReason;
import br.com.gestorcobranca.negotiation.domain.model.simulation.NegotiationSimulation;
import br.com.gestorcobranca.negotiation.domain.repository.SimulationRepository;
import br.com.gestorcobranca.negotiation.domain.service.DebtCalculator;
import br.com.gestorcobranca.negotiation.domain.service.OfferCalculator;
import br.com.gestorcobranca.negotiation.domain.service.TermsEvaluator;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class SimulationService implements NegotiationSimulationUseCases {

    static final String RESOURCE = "negotiation_simulation";
    private static final Set<String> CHANNELS = Set.of("PHONE", "WHATSAPP", "EMAIL", "SMS", "CHAT", "PORTAL", "IN_PERSON");

    private final NegotiationGuard guard;
    private final SimulationRepository simulations;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final NegotiationMetrics metrics;
    private final JsonCodec json;
    private final Clock clock;
    private final OfferCalculator offerCalculator = new OfferCalculator();

    SimulationService(NegotiationGuard guard, SimulationRepository simulations, AccessScope accessScope,
                      CurrentActor currentActor, AuditTrail auditTrail, BusinessEventLogger businessEvents,
                      NegotiationMetrics metrics, JsonCodec json, Clock clock) {
        this.guard = guard;
        this.simulations = simulations;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public SimulationView simulate(SimulateCommand command) {
        Instant started = Instant.now(clock);
        accessScope.requirePermission(Permission.NEGOTIATION_SIMULATE);
        Actor actor = currentActor.get();
        String channel = channel(command.channel());
        int requested = command.requestedLevel() == null ? 0 : command.requestedLevel();
        Guard.require(requested >= 0, "negotiation.level_invalid", "Nível inválido");
        Justification justification = justification(command.justification(), requested > 0);

        Context ctx = guard.prepare(actor, new NegotiationGuard.Request(command.creditorId(), command.debtorId(),
                command.titleIds(), command.referenceDate(), null), false);
        PolicyRules rules = ctx.rules();
        AuthorityLimits limits = ctx.limits();
        Guard.require(requested <= rules.topLevel(), "negotiation.level_unknown",
                "A escada da política vai até o nível " + rules.topLevel());
        int maxLevel = TermsEvaluator.maxLevel(rules, limits);
        int granted = Math.min(requested, maxLevel);
        LocalDate reference = ctx.composition().referenceDate();

        List<OfferView> offers = new ArrayList<>();
        Money bestOffer = null;
        for (int level = 0; level <= granted; level++) {
            ConcessionLevel concession = rules.level(level).orElseThrow();
            for (PlanRequest plan : plans(command.plans(), rules, level, limits, reference)) {
                Evaluation e = guard.evaluate(ctx, level, level == 0 || justification != null,
                        TermsEvaluator.authorityLimit(rules, level, plan.installments(), limits), plan);
                OfferView offer = SimulationViews.offer(e, concession.name());
                offers.add(offer);
                if (offer.valid() && (bestOffer == null || e.plan().total().isLessThan(bestOffer))) {
                    bestOffer = e.plan().total();
                }
            }
        }
        boolean envelopeVisible = !actor.isAssistant() && limits.maxConcessionLevel() >= rules.topLevel();
        SimulationResult result = new SimulationResult(command.creditorId(), command.debtorId(), ctx.portfolioId(),
                new PolicyRef(ctx.policy().id(), ctx.policy().policy().getPolicyVersion(), ctx.resolved().overrideCode()),
                reference, actorRef(ctx), SimulationViews.limits(rules, limits), requested, granted, requested > maxLevel,
                granted < maxLevel, justification == null ? null : new JustificationInput(justification.reason().name(),
                justification.note()), channel, SimulationViews.composition(ctx.composition()),
                envelopeVisible ? envelope(ctx) : null, offers, SimulationViews.excluded(ctx.excluded()),
                ctx.composition().notes(), DebtCalculator.ENGINE_VERSION);

        NegotiationSimulation simulation = simulations.save(new NegotiationSimulation(new NegotiationSimulation.Header(
                command.creditorId(), command.debtorId(), ctx.portfolioId(), ctx.policy().id(),
                ctx.policy().policy().getPolicyVersion(), ctx.resolved().overrideCode(), reference, actor.type().name(),
                actor.id(), ctx.authority().profile().getId(), limits.profileCode(), requested, granted,
                justification == null ? null : justification.reason(), justification == null ? null : justification.note(),
                ctx.composition().titles().size(), ctx.composition().maxDaysOverdue(), ctx.composition().total().total(),
                bestOffer, channel), json.toJson(result), DebtCalculator.ENGINE_VERSION));

        long validOffers = offers.stream().filter(OfferView::valid).count();
        if (requested > 0) {
            auditTrail.record(AuditEntry.of("negotiation.concession_escalated", RESOURCE, simulation.getId())
                    .creditor(command.creditorId()).created("requestedLevel", requested).created("grantedLevel", granted)
                    .created("reason", justification.reason()).meta("actorType", actor.type())
                    .meta("profile", limits.profileCode()).meta("policyVersion", ctx.policy().policy().getPolicyVersion()));
            metrics.concessionEscalated(actor.type().name(), requested, justification.reason().name());
        }
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.NEGOTIATION_SIMULATED)
                .creditor(command.creditorId()).portfolio(ctx.portfolioId()).debtor(command.debtorId())
                .reference(RESOURCE, simulation.getId()).channel(channel)
                .amount(ctx.composition().total().total())
                .attr("actorType", actor.type())
                .attr("profileCode", limits.profileCode())
                .attr("policyVersion", ctx.policy().policy().getPolicyVersion())
                .attr("override", ctx.resolved().overrideCode())
                .attr("requestedLevel", requested)
                .attr("grantedLevel", granted)
                .attr("levelCapped", requested > maxLevel)
                .attr("escalationReason", justification == null ? null : justification.reason())
                .attr("titles", ctx.composition().titles().size())
                .attr("excludedTitles", ctx.excluded().size())
                .attr("offers", offers.size())
                .attr("validOffers", validOffers)
                .attr("daysOverdue", ctx.composition().maxDaysOverdue())
                .attr("bestOfferAmount", bestOffer == null ? null : bestOffer.amount()));
        metrics.simulated(actor.type().name(), validOffers > 0 ? "OFFERED" : "NO_VALID_OFFER", offers.size(),
                Duration.between(started, Instant.now(clock)));
        return new SimulationView(simulation.getId(), simulation.getCreatedAt(), result);
    }

    @Override
    @Transactional(readOnly = true)
    public SimulationView get(UUID simulationId) {
        accessScope.requirePermission(Permission.NEGOTIATION_SIMULATE);
        NegotiationSimulation simulation = simulations.findById(simulationId)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, simulationId));
        accessScope.requireVisible(RESOURCE, simulationId, simulation.getCreditorId());
        Actor actor = currentActor.get();
        if (actor.isAssistant() && !actor.id().equals(simulation.getActorId())) {
            // o assistente só relê as próprias simulações (minimização de dados ao modelo)
            throw new ResourceNotFoundException(RESOURCE, simulationId);
        }
        return new SimulationView(simulation.getId(), simulation.getCreatedAt(),
                json.fromJson(simulation.getCalculation(), SimulationResult.class));
    }

    @Override
    @Transactional(readOnly = true)
    public ValidationView validate(ValidateCommand command) {
        accessScope.requirePermission(Permission.NEGOTIATION_SIMULATE);
        Guard.require(command.plan() != null, "negotiation.plan_required", "Forma de pagamento obrigatória");
        Guard.require(command.level() >= 0, "negotiation.level_invalid", "Nível inválido");
        channel(command.channel());
        Justification justification = command.level() > 0 && command.justification() != null
                ? justification(command.justification(), true) : null;
        Context ctx = guard.prepare(currentActor.get(), new NegotiationGuard.Request(command.creditorId(),
                command.debtorId(), command.titleIds(), command.referenceDate(), command.policyId()), true);
        PlanRequest plan = planRequest(command.plan(), ctx.composition().referenceDate());
        Evaluation e = guard.validate(ctx, command.level(), justification != null, SimulationViews.rates(command.discounts())
                .validate("negotiation.discount_invalid", "Desconto"), plan);
        String levelName = ctx.rules().level(command.level()).map(ConcessionLevel::name).orElse(null);
        return new ValidationView(e.outcome().name(),
                new PolicyRef(ctx.policy().id(), ctx.policy().policy().getPolicyVersion(), ctx.resolved().overrideCode()),
                actorRef(ctx), SimulationViews.limits(ctx.rules(), ctx.limits()), SimulationViews.offer(e, levelName),
                e.violations().stream().map(SimulationViews::violation).toList(), SimulationViews.excluded(ctx.excluded()));
    }

    /** Formas de pagamento: as pedidas, ou à vista e o maior parcelamento permitido no nível. */
    private static List<PlanRequest> plans(List<PlanInput> requested, PolicyRules rules, int level, AuthorityLimits limits,
                                           LocalDate reference) {
        if (!requested.isEmpty()) {
            Guard.require(requested.size() <= 12, "negotiation.too_many_plans", "No máximo 12 formas de pagamento");
            return requested.stream().map(p -> planRequest(p, reference)).toList();
        }
        Set<Integer> counts = new LinkedHashSet<>(List.of(1, TermsEvaluator.maxInstallments(rules, level, limits)));
        return counts.stream().map(n -> new PlanRequest(n, null, reference)).toList();
    }

    static PlanRequest planRequest(PlanInput input, LocalDate reference) {
        return new PlanRequest(input.installments(), input.downPayment() == null ? null : Money.of(input.downPayment()),
                input.firstDueDate() == null ? reference : input.firstDueDate());
    }

    /** Envelope completo da política por faixa de forma de pagamento (sem o recorte da escada/alçada). */
    private List<EnvelopeTierView> envelope(Context ctx) {
        List<EnvelopeTierView> tiers = new ArrayList<>();
        for (DiscountTier tier : ctx.rules().discountTiers()) {
            PricedDebt priced = offerCalculator.price(ctx.composition(), ctx.rules().charges(), tier.maxDiscount());
            tiers.add(new EnvelopeTierView(tier.upToInstallments(), SimulationViews.rates(tier.maxDiscount()),
                    priced.discount().total().amount(), priced.net().total().amount()));
        }
        return tiers;
    }

    private static ActorRef actorRef(Context ctx) {
        return new ActorRef(ctx.actor().type().name(), ctx.actor().id(), ctx.authority().profile().getId(),
                ctx.authority().profile().getCode());
    }

    private record Justification(ConcessionReason reason, String note) {
    }

    private static Justification justification(JustificationInput input, boolean required) {
        if (input == null || input.reason() == null || input.reason().isBlank()) {
            Guard.require(!required, "negotiation.justification_required",
                    "Subir de nível na escada exige justificativa");
            return null;
        }
        ConcessionReason reason;
        try {
            reason = ConcessionReason.valueOf(input.reason().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("negotiation.justification_reason_invalid",
                    "Motivo de concessão inválido: " + input.reason());
        }
        String note = input.note() == null || input.note().isBlank() ? null : input.note().trim();
        Guard.require(reason != ConcessionReason.OTHER || note != null, "negotiation.justification_note_required",
                "O motivo OTHER exige observação");
        Guard.require(note == null || note.length() <= 500, "negotiation.justification_note_too_long",
                "Observação até 500 caracteres");
        return new Justification(reason, note);
    }

    static String channel(String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        String normalized = channel.trim().toUpperCase();
        Guard.require(CHANNELS.contains(normalized), "negotiation.channel_invalid", "Canal inválido: " + channel);
        return normalized;
    }
}
