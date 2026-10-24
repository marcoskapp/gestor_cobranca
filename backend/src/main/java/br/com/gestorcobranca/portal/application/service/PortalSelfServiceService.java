package br.com.gestorcobranca.portal.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementFilter;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.InstallmentView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.JustificationInput;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.OfferView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.PlanInput;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.SimulationResult;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.SimulationView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.ValidateCommand;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.ValidationView;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.CreateProposalCommand;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.ProposalView;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.PortfolioView;
import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases.CreateInstructionCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases.InstructionFilter;
import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases.InstructionView;
import br.com.gestorcobranca.portal.application.port.in.PortalSelfServiceUseCases;
import br.com.gestorcobranca.portal.application.port.out.PortalAgreementLedger;
import br.com.gestorcobranca.portal.application.service.PortalContext.Current;
import br.com.gestorcobranca.portal.domain.model.PortalChannel;
import br.com.gestorcobranca.portal.domain.model.PortalConfiguration;
import br.com.gestorcobranca.portal.domain.model.PortalSession;
import br.com.gestorcobranca.portal.domain.model.ServiceRequest;
import br.com.gestorcobranca.portal.domain.model.ServiceRequestReason;
import br.com.gestorcobranca.portal.domain.repository.PortalSessionRepository;
import br.com.gestorcobranca.portal.domain.repository.ServiceRequestRepository;
import br.com.gestorcobranca.portal.domain.service.AccessSecrets;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.logging.MdcKeys;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Autoatendimento do devedor. Toda consulta e todo comando usam o devedor e o credor da sessão
 * ({@link PortalContext}); ids informados (carteira, acordo, simulação) são conferidos contra eles e
 * contra as carteiras habilitadas — fora disso, 404. A negociação usa os casos de uso do negotiation
 * com o ator {@code DEBTOR} (alçada do canal, perfil {@code PORTAL}), que revalidam tudo no
 * {@code NegotiationGuard}.
 */
@Service
class PortalSelfServiceService implements PortalSelfServiceUseCases {

    private static final Logger LOG = LoggerFactory.getLogger(PortalSelfServiceService.class);
    static final Set<String> ACTIVE_AGREEMENT = Set.of("FORMALIZED", "IN_PAYMENT");
    static final Set<String> OPEN_INSTALLMENT = Set.of("OPEN", "PARTIALLY_PAID", "OVERDUE");
    static final Set<String> OPEN_PIX = Set.of("PENDING", "ACTIVE");
    static final int MAX_PLANS = 6;
    static final int MAX_REQUESTS_PER_DAY = 5;
    static final String CHANNEL = "PORTAL";
    static final String PIX_TYPE = "PIX_IMMEDIATE";

    private final PortalContext context;
    private final PortalDebts debts;
    private final NegotiationSimulationUseCases simulations;
    private final ProposalUseCases proposals;
    private final AgreementUseCases agreements;
    private final PaymentInstructionUseCases instructions;
    private final PortfolioUseCases portfolios;
    private final PortalAgreementLedger ledger;
    private final PortalSessionRepository sessions;
    private final ServiceRequestRepository serviceRequests;
    private final JobQueue jobs;
    private final BusinessEventLogger businessEvents;
    private final PortalMetrics metrics;
    private final TransactionTemplate transaction;
    private final Clock clock;

    PortalSelfServiceService(PortalContext context, PortalDebts debts, NegotiationSimulationUseCases simulations,
                       ProposalUseCases proposals, AgreementUseCases agreements, PaymentInstructionUseCases instructions,
                       PortfolioUseCases portfolios, PortalAgreementLedger ledger, PortalSessionRepository sessions,
                       ServiceRequestRepository serviceRequests, JobQueue jobs, BusinessEventLogger businessEvents,
                       PortalMetrics metrics, PlatformTransactionManager transactionManager, Clock clock) {
        this.context = context;
        this.debts = debts;
        this.simulations = simulations;
        this.proposals = proposals;
        this.agreements = agreements;
        this.instructions = instructions;
        this.portfolios = portfolios;
        this.ledger = ledger;
        this.sessions = sessions;
        this.serviceRequests = serviceRequests;
        this.jobs = jobs;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // ------------------------------------------------------------------ dívidas

    /**
     * Sem transação envolvente: cada cotação roda na própria transação, para que uma carteira sem
     * condição (política ausente, títulos inelegíveis) não invalide a consulta das demais.
     */
    @Override
    public DebtsView debts() {
        Current c = context.require();
        UUID debtorId = c.session().getDebtorId();
        Map<UUID, List<TitleView>> byPortfolio = debts.titlesInPortal(c.config(), debtorId).stream()
                .collect(Collectors.groupingBy(TitleView::portfolioId, LinkedHashMap::new, Collectors.toList()));
        Map<UUID, String> names = portfolioNames(byPortfolio.keySet());
        List<AgreementView> active = activeAgreements(c);
        List<PortfolioDebt> result = new ArrayList<>();
        for (Map.Entry<UUID, List<TitleView>> e : byPortfolio.entrySet()) {
            UUID portfolioId = e.getKey();
            List<TitleView> titles = e.getValue();
            BigDecimal open = titles.stream().map(TitleView::openBalance).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Optional<AgreementView> agreement = active.stream().filter(a -> portfolioId.equals(a.portfolioId())).findFirst();
            BigDecimal updated = null;
            String unavailable = null;
            if (agreement.isPresent()) {
                unavailable = "AGREEMENT_ACTIVE";
            } else {
                try {
                    updated = quote(c, openIds(titles)).offer().negotiated();
                } catch (DomainException ex) {
                    unavailable = "negotiation.policy_missing".equals(ex.code()) ? "NO_POLICY" : "NOT_ELIGIBLE";
                }
            }
            result.add(new PortfolioDebt(portfolioId, names.get(portfolioId), open, updated, unavailable == null,
                    unavailable, agreement.map(AgreementView::id).orElse(null),
                    titles.stream().map(PortalSelfServiceService::title).toList()));
        }
        return new DebtsView(c.config().getDisplayName(), result);
    }

    /** Valor atualizado hoje (sem desconto, à vista) pelo motor do negotiation, sem gravar simulação. */
    private ValidationView quote(Current c, List<UUID> titleIds) {
        return simulations.validate(new ValidateCommand(c.config().getCreditorId(), c.session().getDebtorId(), titleIds,
                null, null, 0, null, null, new PlanInput(1, null, null), CHANNEL));
    }

    // ------------------------------------------------------------------ ofertas e simulação

    @Override
    @Transactional
    public OffersView offers(UUID portfolioId) {
        Current c = context.require();
        List<UUID> titleIds = negotiableTitles(c, portfolioId);
        ValidationView quote = quote(c, titleIds);
        int maxInstallments = Math.min(MAX_PLANS, quote.limits().maxInstallments());
        List<PlanInput> plans = new ArrayList<>();
        for (int n = 1; n <= maxInstallments; n++) {
            plans.add(new PlanInput(n, null, null));
        }
        return simulate(c, portfolioId, titleIds, quote, plans);
    }

    @Override
    @Transactional
    public OffersView simulate(UUID portfolioId, SimulateCommand command) {
        Current c = context.require();
        Guard.require(command != null && command.installments() >= 1, "portal.installments_invalid",
                "Escolha em quantas vezes quer pagar");
        List<UUID> titleIds = negotiableTitles(c, portfolioId);
        ValidationView quote = quote(c, titleIds);
        int max = Math.min(MAX_PLANS, quote.limits().maxInstallments());
        Guard.require(command.installments() <= max, "portal.installments_unavailable",
                "Pelo portal, o pagamento pode ser feito em até " + max + " vez(es)");
        return simulate(c, portfolioId, titleIds, quote,
                List.of(new PlanInput(command.installments(), command.downPayment(), command.firstDueDate())));
    }

    private OffersView simulate(Current c, UUID portfolioId, List<UUID> titleIds, ValidationView quote,
                                List<PlanInput> plans) {
        int level = quote.limits().maxLevel();
        SimulationView simulation = simulations.simulate(new NegotiationSimulationUseCases.SimulateCommand(
                c.config().getCreditorId(), c.session().getDebtorId(), titleIds, null, level,
                level > 0 ? new JustificationInput("SELF_SERVICE_PREAUTHORIZED", null) : null, plans, CHANNEL));
        Instant now = Instant.now(clock);
        PortalSession session = reload(c);
        if (session.markSimulated(now)) {
            sessions.save(session);
            metrics.funnel(PortalMetrics.Step.SIMULATION);
        }
        SimulationResult r = simulation.result();
        List<OfferSummary> offers = bestOffers(r);
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PORTAL_SIMULATED).creditor(c.config().getCreditorId())
                .portfolio(portfolioId).debtor(c.session().getDebtorId()).channel(CHANNEL)
                .reference("negotiation_simulation", simulation.id()).amount(r.composition().totalAmount())
                .attr("offers", offers.size()).attr("grantedLevel", r.grantedLevel())
                .attr("plans", plans.size()).attr("daysOverdue", r.composition().maxDaysOverdue()));
        return new OffersView(simulation.id(), portfolioId, r.composition().totalAmount(), r.referenceDate(),
                Math.min(MAX_PLANS, quote.limits().maxInstallments()),
                r.maxDaysToFirstPayment() == null ? 0 : r.maxDaysToFirstPayment(),
                r.proposalValidityDays() == null ? 0 : r.proposalValidityDays(),
                r.breakRule() == null ? null : r.breakRule().overdueInstallments(),
                r.breakRule() == null ? null : r.breakRule().toleranceDays(), offers);
    }

    /** Só ofertas válidas e dentro da alçada do canal; por número de pagamentos, a de menor total. */
    static List<OfferSummary> bestOffers(SimulationResult r) {
        BigDecimal updated = r.composition().totalAmount();
        Map<Integer, OfferView> best = new LinkedHashMap<>();
        r.offers().stream().filter(o -> o.valid() && o.withinAuthority())
                .sorted(Comparator.comparingInt(OfferView::installments).thenComparing(OfferView::total)
                        .thenComparing(Comparator.comparingInt(OfferView::level).reversed()))
                .forEach(o -> best.putIfAbsent(o.installments(), o));
        return best.values().stream().map(o -> summary(o, updated)).toList();
    }

    static OfferSummary summary(OfferView o, BigDecimal updated) {
        BigDecimal installmentAmount = o.schedule().size() > 1 ? o.schedule().get(1).amount() : o.total();
        BigDecimal percent = updated == null || updated.signum() == 0 ? BigDecimal.ZERO
                : o.discountTotal().multiply(BigDecimal.valueOf(100)).divide(updated, 1, RoundingMode.DOWN);
        return new OfferSummary(o.level(), o.installments(), o.downPayment(), installmentAmount, o.total(),
                o.discountTotal(), percent, o.financingInterest(), o.schedule().getFirst().dueDate(),
                o.schedule().stream().map(p -> new ScheduleItem(p.number(), p.dueDate(), p.amount())).toList());
    }

    // ------------------------------------------------------------------ aceite e formalização

    private record Formalized(AgreementView agreement, OfferView offer) {
    }

    @Override
    public AcceptanceResult accept(AcceptCommand command) {
        Current c = context.require();
        Guard.require(command != null && command.simulationId() != null, "portal.simulation_required",
                "Escolha uma das condições oferecidas");
        Guard.require(command.termsAccepted(), "portal.terms_not_accepted",
                "Para fechar o acordo, confirme que leu e aceita os termos");
        Formalized formalized = transaction.execute(status -> formalize(c, command));
        Objects.requireNonNull(formalized);
        AgreementView agreement = formalized.agreement();
        IssuedPix pix = issuePixSafely(c, agreement, firstOpenInstallment(agreement).orElse(null));
        return new AcceptanceResult(detail(agreement), pix.view(), pix.error());
    }

    /** Proposta + aceite (termo PDF, evidência PORTAL/IP/agente/instante/hash) + formalização, numa transação. */
    private Formalized formalize(Current c, AcceptCommand command) {
        SimulationResult sim = simulations.get(command.simulationId()).result();
        if (!sim.creditorId().equals(c.config().getCreditorId()) || !c.config().coversPortfolio(sim.portfolioId())) {
            throw new ResourceNotFoundException("negotiation_simulation", command.simulationId());
        }
        requireNoActiveAgreement(c, sim.portfolioId());
        OfferView offer = sim.offers().stream()
                .filter(o -> o.level() == command.level() && o.installments() == command.installments()
                        && o.valid() && o.withinAuthority()
                        && (command.downPayment() == null || o.downPayment().compareTo(command.downPayment()) == 0)
                        && (command.firstDueDate() == null || o.schedule().getFirst().dueDate().equals(command.firstDueDate())))
                .findFirst()
                .orElseThrow(() -> refused("not_offered"));
        ProposalView proposal;
        try {
            proposal = proposals.create(new CreateProposalCommand(command.simulationId(), command.level(), null,
                    new PlanInput(command.installments(), command.downPayment(), command.firstDueDate()), null, null,
                    false, CHANNEL));
        } catch (BusinessRuleException e) {
            if ("proposal.outside_self_service".equals(e.code()) || "proposal.conditions_rejected".equals(e.code())) {
                throw refused("guard_" + e.code().substring("proposal.".length()));
            }
            throw e;
        }
        if (proposal.totalAmount().compareTo(offer.total()) != 0) {
            throw new ConflictException("portal.offer_changed",
                    "As condições mudaram desde a simulação. Veja as condições atualizadas.");
        }
        Instant now = Instant.now(clock);
        String reference = "portal-session:" + c.session().getId();
        proposals.accept(proposal.id(), new ProposalUseCases.AcceptCommand(CHANNEL, reference, MDC.get(MdcKeys.CLIENT_IP),
                MDC.get(MdcKeys.USER_AGENT)));
        AgreementView agreement = proposals.formalize(proposal.id());
        ledger.record(new PortalAgreementLedger.Entry(agreement.id(), c.session().getId(), c.config().getId(),
                c.config().getCreditorId(), agreement.portfolioId(), c.session().getDebtorId(), proposal.id(),
                agreement.installments().size(), agreement.totalAmount(), now, null));
        PortalSession session = reload(c);
        if (session.markAccepted(now)) {
            sessions.save(session);
            metrics.funnel(PortalMetrics.Step.ACCEPTANCE);
        }
        metrics.agreementFormalized(agreement.installments().size() == 1 ? "CASH" : "INSTALLMENTS");
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PORTAL_AGREEMENT_FORMALIZED)
                .creditor(agreement.creditorId()).portfolio(agreement.portfolioId()).debtor(agreement.debtorId())
                .collectionCase(agreement.caseId()).reference("negotiation_agreement", agreement.id())
                .channel(CHANNEL).amount(agreement.totalAmount())
                .attr("installments", agreement.installments().size()).attr("concessionLevel", agreement.concessionLevel())
                .attr("discountAmount", agreement.discountAmount()).attr("origin", agreement.origin())
                .attr("method", c.session().getMethod().name())
                .durationMs(Duration.between(c.session().getCreatedAt(), now).toMillis()));
        return new Formalized(agreement, offer);
    }

    private BusinessRuleException refused(String reason) {
        metrics.offerRefused(reason);
        return new BusinessRuleException("portal.offer_unavailable",
                "Essa condição não está disponível pelo portal. Escolha uma das condições oferecidas ou fale com um atendente.");
    }

    // ------------------------------------------------------------------ acordos e Pix

    @Override
    @Transactional(readOnly = true)
    public List<AgreementSummary> agreements() {
        Current c = context.require();
        Set<UUID> closedHere = ledger.agreementIdsOf(c.session().getDebtorId(), c.config().getCreditorId());
        return allAgreements(c).stream().map(a -> {
            Optional<InstallmentView> next = firstOpenInstallment(a);
            return new AgreementSummary(a.id(), a.portfolioId(), a.status(), a.totalAmount(), a.paidAmount(),
                    a.installments().size(), next.map(InstallmentView::dueDate).orElse(null),
                    next.map(i -> i.amount().subtract(i.paidAmount())).orElse(null), a.formalizedAt(),
                    closedHere.contains(a.id()));
        }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AgreementDetail agreement(UUID agreementId) {
        Current c = context.require();
        return detail(ownAgreement(c, agreementId));
    }

    @Override
    public PixView pix(UUID agreementId, UUID installmentId) {
        Current c = context.require();
        AgreementView agreement = transaction.execute(status -> ownAgreement(c, agreementId));
        Objects.requireNonNull(agreement);
        Guard.requireState(ACTIVE_AGREEMENT.contains(agreement.status()), "portal.agreement_closed",
                "Este acordo não está mais em pagamento");
        InstallmentView installment = agreement.installments().stream().filter(i -> i.id().equals(installmentId))
                .findFirst().orElseThrow(() -> new ResourceNotFoundException("agreement_installment", installmentId));
        Guard.requireState(OPEN_INSTALLMENT.contains(installment.status()), "portal.installment_paid",
                "Esta parcela já foi paga");
        IssuedPix pix = issuePixSafely(c, agreement, installment);
        if (pix.view() == null) {
            throw new ConflictException("portal.pix_unavailable",
                    "Não foi possível gerar o Pix agora. Tente de novo em alguns minutos.");
        }
        return pix.view();
    }

    private record IssuedPix(PixView view, String error) {
    }

    /** Pix da parcela em transação própria: falha não desfaz o acordo (o devedor pede a 2ª via depois). */
    private IssuedPix issuePixSafely(Current c, AgreementView agreement, InstallmentView installment) {
        if (installment == null) {
            return new IssuedPix(null, "portal.installment_paid");
        }
        try {
            PixView view = transaction.execute(status -> issuePix(c, agreement, installment));
            return new IssuedPix(view, null);
        } catch (DomainException e) {
            metrics.pix("FAILED");
            LOG.atWarn().addKeyValue("agreementId", agreement.id()).addKeyValue("reason", e.code())
                    .log("Pix do portal não gerado");
            return new IssuedPix(null, e.code());
        }
    }

    private PixView issuePix(Current c, AgreementView agreement, InstallmentView installment) {
        Optional<InstructionView> open = openPix(c, agreement.id()).stream()
                .filter(i -> installment.id().equals(i.installmentId())).findFirst();
        InstructionView instruction = open.orElseGet(() -> instructions.create(new CreateInstructionCommand(PIX_TYPE,
                agreement.id(), installment.id(), null, null, null, expiresIn(installment.dueDate()), CHANNEL), null));
        metrics.pix(open.isPresent() ? "REUSED" : "ISSUED");
        Instant now = Instant.now(clock);
        PortalSession session = reload(c);
        if (session.markPixIssued(now)) {
            sessions.save(session);
            metrics.funnel(PortalMetrics.Step.PIX_ISSUED);
        }
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PORTAL_PIX_ISSUED).creditor(agreement.creditorId())
                .portfolio(agreement.portfolioId()).debtor(agreement.debtorId())
                .reference("payment_instruction", instruction.id()).channel(CHANNEL).amount(instruction.amount())
                .attr("installmentNumber", installment.number()).attr("reused", open.isPresent()));
        return pix(instruction);
    }

    /**
     * Validade do Pix imediato: até o fim do dia do vencimento da parcela (mín. 1 h, máx. 30 dias);
     * parcela vencida → padrão da assessoria.
     */
    Integer expiresIn(LocalDate dueDate) {
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        if (dueDate == null || dueDate.isBefore(today)) {
            return null;
        }
        Instant endOfDue = ZonedDateTime.of(dueDate.plusDays(1).atStartOfDay(), TimeConfig.BUSINESS_ZONE).toInstant();
        long seconds = Duration.between(Instant.now(clock), endOfDue).toSeconds();
        return (int) Math.max(3_600, Math.min(seconds, Duration.ofDays(30).toSeconds()));
    }

    @Override
    @Transactional(readOnly = true)
    public TermFile term(UUID agreementId) {
        Current c = context.require();
        ownAgreement(c, agreementId);
        ProposalUseCases.TermContent t = agreements.openTerm(agreementId);
        return new TermFile(t.fileName(), t.contentType(), t.size(), t.sha256(), t.content());
    }

    // ------------------------------------------------------------------ atendimento humano

    @Override
    @Transactional
    public ServiceRequestView requestService(ServiceRequestCommand command) {
        Current c = context.require();
        Guard.require(command != null, "portal.request_required", "Diga como podemos ajudar");
        ServiceRequestReason reason = parse(ServiceRequestReason.class, command.reason(), "portal.request_reason_invalid");
        PortalChannel channel = PortalAccessService.channel(command.preferredChannel());
        if (command.portfolioId() != null && !c.config().coversPortfolio(command.portfolioId())) {
            throw new ResourceNotFoundException("portfolio", command.portfolioId());
        }
        Instant since = Instant.now(clock).minus(Duration.ofDays(1));
        long recent = serviceRequests.findByDebtorIdAndCreditorIdOrderByCreatedAtDesc(c.session().getDebtorId(),
                c.config().getCreditorId()).stream().filter(r -> r.getCreatedAt().isAfter(since)).count();
        Guard.require(recent < MAX_REQUESTS_PER_DAY, "portal.request_limit",
                "Você já fez vários pedidos hoje. Um atendente vai responder pelo canal escolhido.");
        String protocol = AccessSecrets.newProtocol();
        while (serviceRequests.existsByProtocol(protocol)) {
            protocol = AccessSecrets.newProtocol();
        }
        ServiceRequest request = serviceRequests.save(new ServiceRequest(c.config().getId(), c.session().getId(),
                c.config().getCreditorId(), command.portfolioId(), c.session().getDebtorId(), protocol, reason, channel,
                command.note()));
        jobs.enqueue(ServiceRequestDispatchJob.TYPE, new ServiceRequestDispatchJob.Payload(request.getId()), null,
                "portal-request:" + request.getId());
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PORTAL_SERVICE_REQUESTED).creditor(request.getCreditorId())
                .portfolio(request.getPortfolioId()).debtor(request.getDebtorId())
                .reference("portal_service_request", request.getId()).channel(CHANNEL)
                .attr("reason", reason.name()).attr("preferredChannel", channel.name())
                .attr("withNote", request.getNote() != null));
        metrics.serviceRequest(reason.name(), "CREATED");
        return view(request);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ServiceRequestView> serviceRequests() {
        Current c = context.require();
        return serviceRequests.findByDebtorIdAndCreditorIdOrderByCreatedAtDesc(c.session().getDebtorId(),
                c.config().getCreditorId()).stream().limit(50).map(PortalSelfServiceService::view).toList();
    }

    // ------------------------------------------------------------------ apoio

    /** Títulos abertos da carteira habilitada, sem acordo em vigor (senão 409). */
    private List<UUID> negotiableTitles(Current c, UUID portfolioId) {
        if (portfolioId == null || !c.config().coversPortfolio(portfolioId)) {
            throw new ResourceNotFoundException("portfolio", portfolioId);
        }
        List<UUID> ids = openIds(debts.titlesInPortal(c.config(), c.session().getDebtorId()).stream()
                .filter(t -> portfolioId.equals(t.portfolioId())).toList());
        if (ids.isEmpty()) {
            throw new ResourceNotFoundException("portfolio", portfolioId);
        }
        requireNoActiveAgreement(c, portfolioId);
        return ids;
    }

    private void requireNoActiveAgreement(Current c, UUID portfolioId) {
        if (activeAgreements(c).stream().anyMatch(a -> portfolioId.equals(a.portfolioId()))) {
            throw new ConflictException("portal.agreement_active",
                    "Já existe um acordo em andamento para esta dívida. Acompanhe em \"Meus acordos\".");
        }
    }

    private static List<UUID> openIds(List<TitleView> titles) {
        return titles.stream().filter(t -> "OPEN".equals(t.status())).map(TitleView::id).toList();
    }

    private List<AgreementView> allAgreements(Current c) {
        return agreements.search(new AgreementFilter(c.config().getCreditorId(), c.session().getDebtorId(), null, null),
                        new PageQuery(0, 100)).content().stream()
                .filter(a -> c.config().coversPortfolio(a.portfolioId())).toList();
    }

    private List<AgreementView> activeAgreements(Current c) {
        return allAgreements(c).stream().filter(a -> ACTIVE_AGREEMENT.contains(a.status())).toList();
    }

    private AgreementView ownAgreement(Current c, UUID agreementId) {
        AgreementView a = agreements.get(agreementId);
        if (!a.creditorId().equals(c.config().getCreditorId()) || !a.debtorId().equals(c.session().getDebtorId())
                || !c.config().coversPortfolio(a.portfolioId())) {
            throw new ResourceNotFoundException("negotiation_agreement", agreementId);
        }
        return a;
    }

    private List<InstructionView> openPix(Current c, UUID agreementId) {
        return instructions.search(new InstructionFilter(c.config().getCreditorId(), c.session().getDebtorId(), agreementId,
                null, null), new PageQuery(0, 100)).content().stream()
                .filter(i -> OPEN_PIX.contains(i.status()) && i.type().startsWith("PIX")).toList();
    }

    private AgreementDetail detail(AgreementView a) {
        Map<UUID, String> pixByInstallment = new LinkedHashMap<>();
        Current c = context.requireSession();
        openPix(c, a.id()).forEach(i -> pixByInstallment.putIfAbsent(i.installmentId(), i.status()));
        return new AgreementDetail(a.id(), a.portfolioId(), a.status(), a.updatedAmount(), a.discountAmount(),
                a.totalAmount(), a.paidAmount(), a.remainingAmount(),
                a.breakRule() == null ? null : a.breakRule().overdueInstallments(),
                a.breakRule() == null ? null : a.breakRule().toleranceDays(), a.formalizedAt(),
                a.acceptance() == null ? null : a.acceptance().acceptedAt(),
                a.acceptance() == null ? null : a.acceptance().termSha256(), a.termAvailable(),
                a.installments().stream().map(i -> new InstallmentItem(i.id(), i.number(), i.dueDate(), i.amount(),
                        i.paidAmount(), i.status(), pixByInstallment.get(i.id()))).toList());
    }

    private static Optional<InstallmentView> firstOpenInstallment(AgreementView a) {
        return a.installments().stream().filter(i -> OPEN_INSTALLMENT.contains(i.status()))
                .min(Comparator.comparingInt(InstallmentView::number));
    }

    private static PixView pix(InstructionView i) {
        return new PixView(i.id(), i.agreementId(), i.installmentId(), i.installmentNumber() == null ? 0 : i.installmentNumber(),
                i.status(), i.amount(), i.expiresAt(), i.pixCopyPaste(), i.paidAt());
    }

    private Map<UUID, String> portfolioNames(Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return portfolios.getAll(ids).stream().collect(Collectors.toMap(PortfolioView::id, PortfolioView::name, (a, b) -> a));
    }

    private PortalSession reload(Current c) {
        return sessions.findById(c.session().getId()).orElseThrow();
    }

    private static TitleSummary title(TitleView t) {
        String suffix = KnowledgeVerifier.suffix(t.externalId());
        return new TitleSummary(t.id(), suffix.isEmpty() ? null : "final " + suffix, t.dueDate(), t.daysOverdue(),
                t.openBalance(), t.status());
    }

    private static ServiceRequestView view(ServiceRequest r) {
        return new ServiceRequestView(r.getId(), r.getProtocol(), r.getReason().name(), r.getPreferredChannel().name(),
                r.getStatus().name(), r.getCreatedAt());
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value, String code) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(code, "Valor inválido: " + value);
        }
    }
}
