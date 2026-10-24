package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.ComponentsView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.SimulationResult;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.TitleCompositionView;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases;
import br.com.gestorcobranca.negotiation.application.port.out.NegotiationLocks;
import br.com.gestorcobranca.negotiation.application.service.AuthorityResolver.Resolution;
import br.com.gestorcobranca.negotiation.application.service.NegotiationGuard.Evaluation;
import br.com.gestorcobranca.negotiation.application.service.NegotiationGuard.Outcome;
import br.com.gestorcobranca.negotiation.application.service.ProposalEvaluator.Result;
import br.com.gestorcobranca.negotiation.domain.model.agreement.AcceptanceEvidence;
import br.com.gestorcobranca.negotiation.domain.model.agreement.Agreement;
import br.com.gestorcobranca.negotiation.domain.model.authority.AuthorityLimits;
import br.com.gestorcobranca.negotiation.domain.model.offer.PlanRequest;
import br.com.gestorcobranca.negotiation.domain.model.policy.ComponentRates;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.ConcessionLevel;
import br.com.gestorcobranca.negotiation.domain.model.proposal.Proposal;
import br.com.gestorcobranca.negotiation.domain.model.proposal.ProposalStatus;
import br.com.gestorcobranca.negotiation.domain.model.simulation.ConcessionReason;
import br.com.gestorcobranca.negotiation.domain.model.simulation.NegotiationSimulation;
import br.com.gestorcobranca.negotiation.domain.repository.AgreementRepository;
import br.com.gestorcobranca.negotiation.domain.repository.ProposalRepository;
import br.com.gestorcobranca.negotiation.domain.repository.SimulationRepository;
import br.com.gestorcobranca.negotiation.domain.service.DebtCalculator;
import br.com.gestorcobranca.negotiation.domain.service.ProposalTerms;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.communication.HandoffRequested;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.logging.MdcKeys;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.shared.storage.FileStorage.StoredFile;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class ProposalService implements ProposalUseCases {

    static final String RESOURCE = "negotiation_proposal";
    /** Motivo e referência do pedido de transferência ({@code HandoffRequested}, contrato do communication). */
    static final String HANDOFF_REASON = "REQUIRES_APPROVAL";
    static final String RESOURCE_REFERENCE = "proposal";
    /** Propostas pendentes examinadas por consulta da fila de aprovação (filtradas pela alçada do aprovador). */
    private static final int QUEUE_SCAN_LIMIT = 1000;

    private final ProposalEvaluator evaluator;
    private final ProposalRepository proposals;
    private final SimulationRepository simulations;
    private final AgreementRepository agreements;
    private final AuthorityResolver authorityResolver;
    private final AgreementRecorder recorder;
    private final TermIssuer termIssuer;
    private final NegotiationLocks locks;
    private final NegotiationViews views;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final AccessLogger accessLogger;
    private final ApplicationEventPublisher events;
    private final FileStorage fileStorage;
    private final NegotiationMetrics metrics;
    private final JsonCodec json;
    private final Clock clock;

    ProposalService(ProposalEvaluator evaluator, ProposalRepository proposals, SimulationRepository simulations,
                    AgreementRepository agreements, AuthorityResolver authorityResolver, AgreementRecorder recorder,
                    TermIssuer termIssuer, NegotiationLocks locks, NegotiationViews views, AccessScope accessScope,
                    CurrentActor currentActor, AuditTrail auditTrail, BusinessEventLogger businessEvents,
                    AccessLogger accessLogger, ApplicationEventPublisher events, FileStorage fileStorage,
                    NegotiationMetrics metrics, JsonCodec json, Clock clock) {
        this.evaluator = evaluator;
        this.proposals = proposals;
        this.simulations = simulations;
        this.agreements = agreements;
        this.authorityResolver = authorityResolver;
        this.recorder = recorder;
        this.termIssuer = termIssuer;
        this.locks = locks;
        this.views = views;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.accessLogger = accessLogger;
        this.events = events;
        this.fileStorage = fileStorage;
        this.metrics = metrics;
        this.json = json;
        this.clock = clock;
    }

    /** Dados de origem de uma proposta (simulação do ator ou sugestão do assistente adotada). */
    private record Source(UUID simulationId, UUID creditorId, UUID debtorId, List<UUID> titleIds, LocalDate referenceDate,
                          int level, ConcessionReason reason, String note, ComponentRates discounts, PlanRequest plan,
                          Money requestedDownPayment, String channel, Agreement replaced, Proposal handoff,
                          boolean copilot) {
    }

    // ------------------------------------------------------------------ criação

    @Override
    public ProposalView create(CreateProposalCommand command) {
        accessScope.requirePermission(Permission.NEGOTIATION_PROPOSE);
        Actor actor = currentActor.get();
        Guard.require(command.simulationId() != null, "proposal.simulation_required", "Simulação obrigatória");
        Guard.require(command.plan() != null, "negotiation.plan_required", "Forma de pagamento obrigatória");
        NegotiationSimulation simulation = simulations.findById(command.simulationId())
                .orElseThrow(() -> new ResourceNotFoundException(SimulationService.RESOURCE, command.simulationId()));
        accessScope.requireVisible(SimulationService.RESOURCE, simulation.getId(), simulation.getCreditorId());
        if (!simulation.getActorId().equals(actor.id()) || !simulation.getActorType().equals(actor.type().name())) {
            throw new BusinessRuleException("proposal.simulation_not_owned",
                    "A proposta deve partir de uma simulação do próprio ator");
        }
        Guard.require(command.level() >= 0 && command.level() <= simulation.getRequestedLevel(),
                "proposal.level_not_simulated", "Nível acima do pedido (e justificado) na simulação");
        SimulationResult result = json.fromJson(simulation.getCalculation(), SimulationResult.class);
        List<UUID> titleIds = result.composition().titles().stream().map(TitleCompositionView::titleId).toList();
        Proposal handoff = command.handoffProposalId() == null ? null : handoff(command.handoffProposalId(), actor,
                simulation.getCreditorId(), simulation.getDebtorId());
        Agreement replaced = replaced(command.replacedAgreementId(), simulation.getCreditorId(), simulation.getDebtorId());
        String channel = SimulationService.channel(command.channel() != null ? command.channel() : simulation.getChannel());
        ComponentRates discounts = command.discounts() == null ? null
                : SimulationViews.rates(command.discounts()).validate("negotiation.discount_invalid", "Desconto");
        PlanRequest plan = SimulationService.planRequest(command.plan(), simulation.getReferenceDate());
        return register(actor, new Source(simulation.getId(), simulation.getCreditorId(), simulation.getDebtorId(), titleIds,
                simulation.getReferenceDate(), command.level(), simulation.getJustificationReason(),
                simulation.getJustificationNote(), discounts, plan, plan.downPayment(), channel, replaced, handoff,
                command.copilot()));
    }

    @Override
    public ProposalView adopt(UUID handoffProposalId, String channel) {
        accessScope.requirePermission(Permission.NEGOTIATION_PROPOSE);
        Actor actor = currentActor.get();
        Proposal h = loadVisible(handoffProposalId);
        Proposal handoff = handoff(h.getId(), actor, h.getCreditorId(), h.getDebtorId());
        LocalDate today = today();
        LocalDate reference = handoff.getReferenceDate().isBefore(today) ? today : handoff.getReferenceDate();
        LocalDate firstDue = handoff.getFirstDueDate().isBefore(reference) ? reference : handoff.getFirstDueDate();
        PlanRequest plan = new PlanRequest(handoff.getInstallments(), handoff.getRequestedDownPayment(), firstDue);
        Agreement replaced = replaced(handoff.getReplacedAgreementId(), handoff.getCreditorId(), handoff.getDebtorId());
        return register(actor, new Source(handoff.getSimulationId(), handoff.getCreditorId(), handoff.getDebtorId(),
                handoff.getTitleIds(), reference, handoff.getLevel(), handoff.getJustificationReason(),
                handoff.getJustificationNote(), SimulationViews.rates(views.discountRates(handoff)), plan,
                handoff.getRequestedDownPayment(),
                SimulationService.channel(channel != null ? channel : handoff.getChannel()), replaced, handoff, false));
    }

    private ProposalView register(Actor actor, Source s) {
        int renegotiationNumber = s.replaced() == null ? 0 : s.replaced().getRenegotiationNumber() + 1;
        Result r = evaluator.evaluate(actor, new ProposalEvaluator.Terms(s.creditorId(), s.debtorId(), s.titleIds(),
                s.referenceDate(), null, false, s.level(), s.level() == 0 || s.reason() != null, s.discounts(), s.plan(),
                s.replaced() == null ? null : s.replaced().getId(), renegotiationNumber));
        if (r.outcome() == Outcome.REJECTED) {
            metrics.proposalCreated(actor.type().name(), "REJECTED");
            throw new BusinessRuleException("proposal.conditions_rejected",
                    "Condições fora da política ou títulos inelegíveis: " + String.join(", ", r.blockingCodes()),
                    Map.<String, Object>of("violations", r.blockingCodes()));
        }
        if (r.outcome() == Outcome.REQUIRES_APPROVAL && DebtorSelfScope.isDebtor(actor)) {
            // autonegociação: só o que o perfil do canal pré-autoriza; acima disso, atendimento humano
            metrics.proposalCreated(actor.type().name(), "OUTSIDE_SELF_SERVICE");
            throw new BusinessRuleException("proposal.outside_self_service",
                    "Condições acima do autorizado para a autonegociação: " + String.join(", ", r.authorityCodes()),
                    Map.<String, Object>of("violations", r.authorityCodes()));
        }
        ProposalStatus status = r.outcome() == Outcome.WITHIN_AUTHORITY ? ProposalStatus.DRAFT
                : actor.isAssistant() ? ProposalStatus.HANDED_OFF : ProposalStatus.PENDING_APPROVAL;
        Evaluation e = r.evaluation();
        var ctx = r.ctx();
        int n = e.plan().installments();
        LocalDate today = ctx.today();
        LocalDate validUntil = today.plusDays(ctx.rules().validity().proposalValidityDays());
        if (e.plan().schedule().getFirst().dueDate().isBefore(validUntil)) {
            validUntil = e.plan().schedule().getFirst().dueDate();
        }
        List<UUID> titleIds = ctx.composition().titleIds();
        String levelName = ctx.rules().level(s.level()).map(ConcessionLevel::name).orElse(null);
        ProposalCalculation calculation = new ProposalCalculation(SimulationViews.composition(ctx.composition()),
                SimulationViews.offer(e, levelName), DebtCalculator.ENGINE_VERSION);
        Proposal proposal = proposals.save(new Proposal(new Proposal.Draft(s.creditorId(), s.debtorId(), ctx.portfolioId(),
                r.caseRef().map(c -> c.caseId()).orElse(null), r.caseRef().map(c -> c.teamId()).orElse(null),
                s.simulationId(), ctx.policy().id(), ctx.policy().policy().getPolicyVersion(), ctx.resolved().overrideCode(),
                ctx.composition().referenceDate(), actor.type().name(), actor.id(), ctx.limits().profileCode(), s.level(),
                s.reason(), s.note(), s.copilot(), s.channel(), titleIds, json.toJson(SimulationViews.rates(r.discounts())),
                n, s.requestedDownPayment(), e.plan().schedule().getFirst().dueDate(), e.priced().gross().total(),
                e.priced().discount().total(), e.plan().negotiated(), e.plan().downPayment(), e.plan().financingInterest(),
                e.plan().total(), ProposalTerms.envelopeUsage(e.priced().discount().total(), r.maxDiscount()),
                ctx.composition().maxDaysOverdue(),
                ProposalTerms.fingerprint(titleIds, r.discounts(), n, s.requestedDownPayment()),
                ProposalTerms.requiredDiscountShare(r.discounts(), ctx.rules().maxDiscountFor(n)),
                ProposalTerms.downPaymentShare(e.plan().downPayment(), e.plan().negotiated(), n),
                json.toJson(r.authorityCodes()), json.toJson(calculation), validUntil,
                s.replaced() == null ? null : s.replaced().getId(), renegotiationNumber,
                s.handoff() == null ? null : s.handoff().getId()), status));

        auditTrail.record(AuditEntry.of("proposal.created", RESOURCE, proposal.getId()).creditor(s.creditorId())
                .created("status", status).created("level", s.level()).created("installments", n)
                .created("totalAmount", e.plan().total().amount()).created("discountAmount", e.priced().discount().total().amount())
                .created("authorityViolations", r.authorityCodes()).meta("actorType", actor.type())
                .meta("profile", ctx.limits().profileCode()).meta("policyVersion", proposal.getPolicyVersion())
                .meta("simulationId", s.simulationId()).meta("replacedAgreementId", proposal.getReplacedAgreementId())
                .meta("handoffProposalId", proposal.getHandoffProposalId()));
        businessEvents.log(base(BusinessEventTypes.PROPOSAL_CREATED, proposal)
                .attr("actorType", actor.type()).attr("profileCode", ctx.limits().profileCode())
                .attr("policyVersion", proposal.getPolicyVersion()).attr("concessionLevel", s.level())
                .attr("installments", n).attr("outcome", r.outcome()).attr("status", status)
                .attr("envelopeUsage", proposal.getEnvelopeUsage().value())
                .attr("discountAmount", e.priced().discount().total().amount())
                .attr("daysOverdue", ctx.composition().maxDaysOverdue())
                .attr("renegotiation", renegotiationNumber > 0).attr("fromHandoff", s.handoff() != null)
                .attr("copilot", s.copilot()));
        if (status == ProposalStatus.PENDING_APPROVAL) {
            businessEvents.log(base(BusinessEventTypes.PROPOSAL_APPROVAL_REQUESTED, proposal)
                    .attr("actorType", actor.type()).attr("profileCode", ctx.limits().profileCode())
                    .attr("concessionLevel", s.level()).attr("reasons", String.join(",", r.authorityCodes())));
        } else if (status == ProposalStatus.HANDED_OFF) {
            businessEvents.log(base(BusinessEventTypes.PROPOSAL_HANDOFF_REQUESTED, proposal)
                    .attr("actorType", actor.type()).attr("concessionLevel", s.level())
                    .attr("reasons", String.join(",", r.authorityCodes())));
            // transferência para humano pelo contrato do communication (a conversa vai à fila; o caso volta aos humanos)
            events.publishEvent(new HandoffRequested(Ids.newId(), actor.tenantId(), Instant.now(clock),
                    proposal.getCaseId(), proposal.getDebtorId(), proposal.getCreditorId(), null, HANDOFF_REASON, null,
                    handoffSummary(proposal, r.authorityCodes()), RESOURCE_REFERENCE, proposal.getId(),
                    actor.type().name(), actor.id()));
            metrics.handoffRequested();
        }
        metrics.proposalCreated(actor.type().name(), status.name());
        return views.proposal(proposal, minimized(actor));
    }

    /** Assistente e devedor no portal recebem a visão mínima (sem observações livres de decisão). */
    static boolean minimized(Actor actor) {
        return actor.isAssistant() || DebtorSelfScope.isDebtor(actor);
    }

    /** Resumo para o operador, sem dados pessoais: condições, valores e violações de alçada. */
    static String handoffSummary(Proposal p, List<String> reasons) {
        String summary = "Proposta " + p.getId() + " do assistente acima da alçada (" + String.join(", ", reasons)
                + "): nível " + p.getLevel() + ", " + p.getInstallments() + " pagamento(s), total R$ "
                + p.getTotalAmount().amount().toPlainString() + ", desconto R$ " + p.getDiscountAmount().amount().toPlainString()
                + ", 1º vencimento " + p.getFirstDueDate() + ". Assuma a sugestão em /negotiation/proposals/" + p.getId()
                + "/adopt.";
        return summary.length() <= 2000 ? summary : summary.substring(0, 2000);
    }

    private Proposal handoff(UUID id, Actor actor, UUID creditorId, UUID debtorId) {
        Guard.require(actor.isUser(), "proposal.handoff_requires_user", "Somente um humano assume a sugestão do assistente");
        Proposal h = loadVisible(id);
        Guard.require(h.getStatus() == ProposalStatus.HANDED_OFF, "proposal.not_handoff",
                "A proposta indicada não é uma sugestão transferida pelo assistente");
        Guard.require(h.getCreditorId().equals(creditorId) && h.getDebtorId().equals(debtorId), "proposal.handoff_mismatch",
                "A sugestão do assistente é de outro devedor ou credor");
        return h;
    }

    private Agreement replaced(UUID agreementId, UUID creditorId, UUID debtorId) {
        if (agreementId == null) {
            return null;
        }
        Agreement a = agreements.findById(agreementId)
                .orElseThrow(() -> new ResourceNotFoundException(AgreementService.RESOURCE, agreementId));
        accessScope.requireVisible(AgreementService.RESOURCE, agreementId, a.getCreditorId());
        Guard.require(a.getCreditorId().equals(creditorId) && a.getDebtorId().equals(debtorId),
                "proposal.replaced_mismatch", "O acordo a renegociar é de outro devedor ou credor");
        Guard.requireState(a.getStatus().isRenegotiable(), "agreement.not_renegotiable",
                "Somente acordo em vigor ou quebrado pode ser renegociado");
        return a;
    }

    // ------------------------------------------------------------------ consultas

    @Override
    @Transactional(readOnly = true)
    public ProposalView get(UUID proposalId) {
        requireReader();
        return views.proposal(loadVisible(proposalId), minimized(currentActor.get()));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProposalView> search(ProposalFilter filter, PageQuery page) {
        requireReader();
        Actor actor = currentActor.get();
        if (filter.creditorId() != null) {
            accessScope.requireCreditor(filter.creditorId());
        }
        Set<UUID> allowed = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of());
        ProposalStatus status = filter.status() == null ? null : enumValue(ProposalStatus.class, filter.status(),
                "proposal.status_invalid");
        return PageResponse.of(proposals.search(allowed, filter.creditorId(),
                DebtorSelfScope.debtorFilter(actor, filter.debtorId()), filter.caseId(), status,
                actor.isAssistant() ? actor.type().name() : filter.actorType(), actor.isAssistant() ? actor.id() : null,
                page.toPageable(Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")))),
                p -> views.proposal(p, minimized(actor)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProposalView> approvalQueue(PageQuery page) {
        accessScope.requirePermission(Permission.NEGOTIATION_APPROVE);
        Actor actor = currentActor.get();
        Guard.require(actor.isUser(), "proposal.approver_must_be_user", "Somente usuários aprovam propostas");
        Set<UUID> allowed = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of());
        Map<UUID, Optional<AuthorityLimits>> limitsByCreditor = new HashMap<>();
        List<Proposal> eligible = new ArrayList<>();
        for (Proposal p : proposals.findPendingApproval(allowed, QUEUE_SCAN_LIMIT)) {
            if ("USER".equals(p.getActorType()) && actor.id().equals(p.getActorId())) {
                continue;
            }
            Optional<AuthorityLimits> limits = limitsByCreditor.computeIfAbsent(p.getCreditorId(), c -> limitsOf(actor, c));
            if (limits.isPresent() && requirement(p).satisfiedBy(limits.get())) {
                eligible.add(p);
            }
        }
        int from = Math.min(page.page() * page.size(), eligible.size());
        int to = Math.min(from + page.size(), eligible.size());
        return PageResponse.of(new PageImpl<>(eligible.subList(from, to), PageRequest.of(page.page(), page.size()),
                eligible.size()), p -> views.proposal(p, false));
    }

    private Optional<AuthorityLimits> limitsOf(Actor actor, UUID creditorId) {
        try {
            return Optional.of(authorityResolver.resolve(actor, creditorId).profile().limits());
        } catch (BusinessRuleException e) {
            return Optional.empty();
        }
    }

    private static ProposalTerms.AuthorityRequirement requirement(Proposal p) {
        return new ProposalTerms.AuthorityRequirement(p.getLevel(), p.getRequiredDiscountShare().value(),
                p.getInstallments(), p.getTotalAmount(), p.getDownPaymentShare().value(), p.getRenegotiationNumber());
    }

    // ------------------------------------------------------------------ aprovação (F16)

    @Override
    public ProposalView approve(UUID proposalId, String note) {
        accessScope.requirePermission(Permission.NEGOTIATION_APPROVE);
        Actor actor = currentActor.get();
        Guard.require(actor.isUser(), "proposal.approver_must_be_user", "Somente usuários aprovam propostas");
        Proposal p = loadVisible(proposalId);
        accessScope.requireCreditor(p.getCreditorId());
        requireOpen(p);
        Resolution approver = requireApprover(actor, p);
        Result r = reevaluate(actor, p);
        if (r.outcome() == Outcome.REQUIRES_APPROVAL) {
            throw new BusinessRuleException("proposal.approver_authority_insufficient",
                    "As condições excedem a alçada do aprovador: " + String.join(", ", r.authorityCodes()),
                    Map.<String, Object>of("violations", r.authorityCodes()));
        }
        Instant now = Instant.now(clock);
        p.approve(actor.id(), approver.profile().getCode(), note, now);
        proposals.save(p);
        decided(p, "proposal.approved", BusinessEventTypes.PROPOSAL_APPROVED, "APPROVED", approver, now);
        return views.proposal(p, false);
    }

    @Override
    public ProposalView reject(UUID proposalId, String reason) {
        accessScope.requirePermission(Permission.NEGOTIATION_APPROVE);
        Actor actor = currentActor.get();
        Guard.require(actor.isUser(), "proposal.approver_must_be_user", "Somente usuários decidem propostas");
        Proposal p = loadVisible(proposalId);
        accessScope.requireCreditor(p.getCreditorId());
        requireOpen(p);
        Resolution approver = requireApprover(actor, p);
        Instant now = Instant.now(clock);
        p.reject(actor.id(), approver.profile().getCode(), reason, now);
        proposals.save(p);
        decided(p, "proposal.rejected", BusinessEventTypes.PROPOSAL_REJECTED, "REJECTED", approver, now);
        return views.proposal(p, false);
    }

    private Resolution requireApprover(Actor actor, Proposal p) {
        Resolution approver = authorityResolver.resolve(actor, p.getCreditorId());
        Guard.require(approver.profile().isCanApprove(), "proposal.approver_not_allowed",
                "O perfil de alçada do usuário não aprova propostas");
        return approver;
    }

    private void decided(Proposal p, String action, String eventType, String decision, Resolution approver, Instant now) {
        Duration wait = Duration.between(p.getCreatedAt(), now);
        auditTrail.record(AuditEntry.of(action, RESOURCE, p.getId()).creditor(p.getCreditorId())
                .change("status", ProposalStatus.PENDING_APPROVAL, p.getStatus())
                .meta("approverProfile", approver.profile().getCode()).meta("level", p.getLevel())
                .meta("totalAmount", p.getTotalAmount().amount()));
        businessEvents.log(base(eventType, p).attr("actorType", p.getActorType())
                .attr("approverProfile", approver.profile().getCode()).attr("concessionLevel", p.getLevel())
                .attr("installments", p.getInstallments()).attr("envelopeUsage", p.getEnvelopeUsage().value())
                .durationMs(wait.toMillis()));
        metrics.proposalDecided(decision, wait);
    }

    // ------------------------------------------------------------------ aceite e formalização

    @Override
    public ProposalView accept(UUID proposalId, AcceptCommand command) {
        accessScope.requirePermission(Permission.AGREEMENT_FORMALIZE);
        Actor actor = currentActor.get();
        Proposal p = loadVisible(proposalId);
        accessScope.requireCreditor(p.getCreditorId());
        requireOpen(p);
        Guard.requireState(p.getStatus() == ProposalStatus.DRAFT || p.getStatus() == ProposalStatus.APPROVED,
                "proposal.not_acceptable", "A proposta não pode ser aceita na situação " + p.getStatus());
        String channel = command == null || command.channel() == null ? null : command.channel().trim().toUpperCase();
        Guard.require(channel != null && AcceptanceEvidence.CHANNELS.contains(channel), "proposal.acceptance_channel_invalid",
                "Informe o canal do aceite (PHONE, WHATSAPP, EMAIL, SMS, CHAT, PORTAL, IN_PERSON)");
        Guard.require(!DebtorSelfScope.isDebtor(actor) || "PORTAL".equals(channel), "proposal.acceptance_channel_invalid",
                "O aceite do devedor no portal é sempre pelo canal PORTAL");
        Result r = reevaluate(actor, p);
        StoredFile term = termIssuer.issue(p, r.evaluation(), r.ctx().rules().breakRule(), channel);
        Instant now = Instant.now(clock);
        String ip = command.clientIp() != null ? command.clientIp() : MDC.get(MdcKeys.CLIENT_IP);
        String userAgent = command.userAgent() != null ? command.userAgent() : MDC.get(MdcKeys.USER_AGENT);
        p.accept(new AcceptanceEvidence(channel, now, actor.type().name(), actor.id(), blankToNull(command.reference()), ip,
                userAgent, term.id(), term.key(), term.sha256(), term.size()), today());
        proposals.save(p);
        auditTrail.record(AuditEntry.of("proposal.accepted", RESOURCE, p.getId()).creditor(p.getCreditorId())
                .change("status", ProposalStatus.DRAFT, ProposalStatus.ACCEPTED).meta("channel", channel)
                .meta("termSha256", term.sha256()).meta("actorType", actor.type()));
        businessEvents.log(base(BusinessEventTypes.PROPOSAL_ACCEPTED, p).channel(channel)
                .attr("actorType", actor.type()).attr("concessionLevel", p.getLevel())
                .attr("installments", p.getInstallments()).attr("approved", p.wasApproved()));
        metrics.proposalAccepted(channel);
        return views.proposal(p, minimized(actor));
    }

    @Override
    public AgreementView formalize(UUID proposalId) {
        Instant started = Instant.now(clock);
        accessScope.requirePermission(Permission.AGREEMENT_FORMALIZE);
        Actor actor = currentActor.get();
        Proposal p = loadVisible(proposalId);
        accessScope.requireCreditor(p.getCreditorId());
        // serializa formalizações do devedor: dois acordos simultâneos sobre os mesmos títulos são impossíveis
        locks.lockDebtor(p.getDebtorId());
        requireOpen(p);
        Guard.requireState(p.getStatus() == ProposalStatus.ACCEPTED, "proposal.not_accepted",
                "A formalização exige o aceite do devedor");
        Resolution closer = authorityResolver.resolve(actor, p.getCreditorId());
        Guard.require(closer.profile().isCanFormalize(), "proposal.formalization_not_allowed",
                "O perfil de alçada do ator não formaliza acordos (a formalização depende de um humano)");
        Result r = reevaluate(actor, p);
        if (r.outcome() == Outcome.REQUIRES_APPROVAL && !p.wasApproved()) {
            throw new BusinessRuleException("proposal.requires_approval",
                    "As condições excedem a alçada de quem formaliza e a proposta não foi aprovada: "
                            + String.join(", ", r.authorityCodes()), Map.<String, Object>of("violations", r.authorityCodes()));
        }
        Proposal handoff = p.getHandoffProposalId() == null ? null : proposals.findById(p.getHandoffProposalId()).orElse(null);
        Agreement agreement = recorder.formalize(p, r, actor, handoff);
        p.formalized(agreement.getId(), today(), Instant.now(clock));
        proposals.save(p);
        auditTrail.record(AuditEntry.of("proposal.formalized", RESOURCE, p.getId()).creditor(p.getCreditorId())
                .change("status", ProposalStatus.ACCEPTED, ProposalStatus.FORMALIZED).meta("agreementId", agreement.getId()));
        metrics.formalization(Duration.between(started, Instant.now(clock)));
        return views.agreement(agreement);
    }

    @Override
    @Transactional(readOnly = true)
    public TermContent openTerm(UUID proposalId) {
        requireReader();
        Proposal p = loadVisible(proposalId);
        AcceptanceEvidence a = p.getAcceptance();
        if (a == null || a.getTermFileKey() == null || agreementTermDeleted(p)) {
            throw new ResourceNotFoundException("agreement_term", proposalId);
        }
        accessLogger.log(AccessEvent.of(AccessEventType.FILE_DOWNLOADED, currentActor.get(), Map.of("resource", RESOURCE,
                "resourceId", proposalId.toString(), "category", FileCategory.AGREEMENT_TERM.name())));
        return new TermContent("termo-acordo-" + p.getId() + ".pdf", "application/pdf",
                a.getTermSize() == null ? -1 : a.getTermSize(), a.getTermSha256(), fileStorage.open(a.getTermFileKey()));
    }

    private boolean agreementTermDeleted(Proposal p) {
        return p.getAgreementId() != null && agreements.findById(p.getAgreementId())
                .map(a -> a.getTermDeletedAt() != null).orElse(false);
    }

    // ------------------------------------------------------------------ apoio

    /**
     * Revalida a proposta com o ator corrente: mesma versão de política, data-base original,
     * títulos ainda elegíveis e o mesmo valor calculado.
     */
    private Result reevaluate(Actor actor, Proposal p) {
        ComponentRates rates = SimulationViews.rates(views.discountRates(p));
        Result r = evaluator.evaluate(actor, new ProposalEvaluator.Terms(p.getCreditorId(), p.getDebtorId(), p.getTitleIds(),
                p.getReferenceDate(), p.getPolicyId(), true, p.getLevel(),
                p.getLevel() == 0 || p.getJustificationReason() != null, rates,
                new PlanRequest(p.getInstallments(), p.getRequestedDownPayment(), p.getFirstDueDate()),
                p.getReplacedAgreementId(), p.getRenegotiationNumber()));
        if (r.outcome() == Outcome.REJECTED) {
            throw new ConflictException("proposal.no_longer_valid",
                    "A proposta deixou de ser válida (política, títulos ou elegibilidade): "
                            + String.join(", ", r.blockingCodes()), Map.<String, Object>of("violations", r.blockingCodes()));
        }
        Evaluation e = r.evaluation();
        if (!e.plan().total().equals(p.getTotalAmount()) || !e.plan().negotiated().equals(p.getNegotiatedAmount())
                || !r.ctx().composition().titleIds().equals(p.getTitleIds())) {
            throw new ConflictException("proposal.conditions_changed",
                    "A posição dos títulos mudou desde a proposta (ex.: pagamento ou ajuste); faça nova simulação");
        }
        return r;
    }

    private void requireOpen(Proposal p) {
        if (p.getStatus().isOpen() && p.isExpiredOn(today())) {
            throw new ConflictException("proposal.expired", "A proposta está fora da validade");
        }
    }

    private void requireReader() {
        Actor actor = currentActor.get();
        if (!actor.has(Permission.AGREEMENT_VIEW) && !actor.has(Permission.NEGOTIATION_PROPOSE)
                && !actor.has(Permission.NEGOTIATION_APPROVE)) {
            accessScope.requirePermission(Permission.AGREEMENT_VIEW);
        }
    }

    Proposal loadVisible(UUID id) {
        Proposal p = proposals.findById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        accessScope.requireVisible(RESOURCE, id, p.getCreditorId());
        Actor actor = currentActor.get();
        if (actor.isAssistant() && !(actor.id().equals(p.getActorId()) && "ASSISTANT".equals(p.getActorType()))) {
            // o assistente só vê as próprias propostas (minimização)
            throw new ResourceNotFoundException(RESOURCE, id);
        }
        DebtorSelfScope.requireOwn(actor, RESOURCE, id, p.getDebtorId(), accessLogger);
        return p;
    }

    private BusinessEvent base(String type, Proposal p) {
        return BusinessEvent.of(type).creditor(p.getCreditorId()).portfolio(p.getPortfolioId()).debtor(p.getDebtorId())
                .collectionCase(p.getCaseId()).reference(RESOURCE, p.getId()).channel(p.getChannel())
                .amount(p.getTotalAmount());
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static <E extends Enum<E>> E enumValue(Class<E> type, String value, String code) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(code, "Valor inválido: " + value);
        }
    }
}
