package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort.ToolCall;
import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort.ToolDefinition;
import br.com.gestorcobranca.assistant.application.port.out.PaymentInstructionPort;
import br.com.gestorcobranca.assistant.domain.model.AllowedFacts;
import br.com.gestorcobranca.assistant.domain.model.HandoffReason;
import br.com.gestorcobranca.assistant.domain.model.SessionMemory;
import br.com.gestorcobranca.assistant.domain.model.SessionMemory.OfferMemo;
import br.com.gestorcobranca.assistant.domain.model.SessionMemory.ProposalMemo;
import br.com.gestorcobranca.assistant.domain.model.ToolCallOutcome;
import br.com.gestorcobranca.assistant.domain.model.TriggerSource;
import br.com.gestorcobranca.assistant.domain.model.TurnOutcome;
import br.com.gestorcobranca.assistant.domain.service.HandoffDetector;
import br.com.gestorcobranca.assistant.domain.service.PersonalDataRedactor;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.CallbackInput;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.PromiseInput;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.RecordInteractionCommand;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.negotiation.application.port.in.AuthorityUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.ComponentsView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.JustificationInput;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.OfferView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.PaymentView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.PlanInput;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.SimulateCommand;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.SimulationView;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.AcceptCommand;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.CreateProposalCommand;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.ProposalView;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.DomainException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Ferramentas expostas ao modelo. Cada uma chama o caso de uso do módulo dono como o ator
 * {@code ASSISTANT} do turno e <strong>valida no servidor</strong> tudo o que o modelo pede: referências
 * só a ofertas/propostas devolvidas pelo motor nesta sessão, valores só do motor, aceite só com
 * manifestação explícita do devedor na mensagem, motivos de concessão e de transferência só dos
 * catálogos. O modelo nunca informa valores de desconto, entrada ou parcelas: escolhe uma oferta
 * calculada. Resultados devolvidos ao modelo levam apenas referências opacas, valores e datas.
 */
@Component
class AssistantToolbox {

    static final String REGISTER_INTENT = "register_intent";
    static final String DEBT_SUMMARY = "get_debt_summary";
    static final String SIMULATE = "simulate_options";
    static final String PROPOSE = "propose_offer";
    static final String ACCEPT = "register_acceptance";
    static final String PROMISE = "register_promise";
    static final String CALLBACK = "schedule_callback";
    static final String TRANSFER = "transfer_to_human";
    static final String PAYMENT = "generate_payment";

    static final Set<String> INTENTS = Set.of("GREETING", "IDENTITY", "BALANCE", "NEGOTIATE", "ACCEPT", "PROMISE",
            "CALLBACK", "QUESTION", "OTHER");
    static final Set<String> CONCESSION_REASONS = Set.of("PAYMENT_CAPACITY", "INCOME_LOSS", "HEALTH_OR_FAMILY",
            "COMPETING_OFFER", "LOW_RECOVERY_PROSPECT", "IMMEDIATE_PAYMENT");
    private static final int MAX_INSTALLMENTS = 60;
    private static final int CALLBACK_MAX_DAYS = 15;

    /**
     * Fim do turno pedido por uma ferramenta.
     *
     * @param transferRequested a transferência já foi pedida por outro caminho (evento {@code HandoffRequested}
     *                          do negotiation)
     * @param debtorNotified    a mensagem de transferência já foi enviada ao devedor
     */
    record Terminal(TurnOutcome outcome, HandoffReason reason, TriggerSource source, String note, String referenceType,
                    UUID referenceId, boolean transferRequested, boolean debtorNotified) {
    }

    record ToolOutcome(ToolCallOutcome outcome, String resultJson, String errorCode, String referenceType,
                       UUID referenceId, Terminal terminal) {
    }

    private final NegotiationSimulationUseCases simulations;
    private final ProposalUseCases proposals;
    private final AuthorityUseCases authority;
    private final InteractionUseCases interactions;
    private final DebtTitleUseCases titles;
    private final ObjectProvider<PaymentInstructionPort> payments;
    private final AssistantChannel channel;
    private final AssistantActors actors;
    private final AssistantCodec codec;
    private final AssistantMetrics metrics;
    private final Clock clock;

    AssistantToolbox(NegotiationSimulationUseCases simulations, ProposalUseCases proposals, AuthorityUseCases authority,
                     InteractionUseCases interactions, DebtTitleUseCases titles,
                     ObjectProvider<PaymentInstructionPort> payments, AssistantChannel channel, AssistantActors actors,
                     AssistantCodec codec, AssistantMetrics metrics, Clock clock) {
        this.simulations = simulations;
        this.proposals = proposals;
        this.authority = authority;
        this.interactions = interactions;
        this.titles = titles;
        this.payments = payments;
        this.channel = channel;
        this.actors = actors;
        this.codec = codec;
        this.metrics = metrics;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ catálogo

    /** Ferramentas disponíveis no estado do turno (modo, identidade, proposta corrente). */
    List<ToolDefinition> available(TurnContext ctx) {
        List<ToolDefinition> tools = new ArrayList<>();
        tools.add(ToolSchemas.REGISTER_INTENT);
        boolean drafts = ctx.mode.drafts();
        boolean confirmed = drafts || ctx.session.identityConfirmed();
        if (confirmed) {
            tools.add(ToolSchemas.DEBT_SUMMARY);
            tools.add(ToolSchemas.SIMULATE);
        }
        if (!drafts) {
            tools.add(ToolSchemas.TRANSFER);
            if (confirmed) {
                tools.add(ToolSchemas.PROPOSE);
                tools.add(ToolSchemas.ACCEPT);
                if (ctx.conversation.caseId() != null) {
                    tools.add(ToolSchemas.PROMISE);
                    tools.add(ToolSchemas.CALLBACK);
                }
                if (payments.getIfAvailable() != null && ctx.memory.proposal() != null
                        && ctx.memory.proposal().agreementId() != null) {
                    tools.add(ToolSchemas.PAYMENT);
                }
            }
        }
        return tools;
    }

    // ------------------------------------------------------------------ execução

    ToolOutcome execute(TurnContext ctx, ToolCall call) {
        long started = System.nanoTime();
        ToolOutcome outcome;
        Map<String, Object> args;
        try {
            args = codec.toMap(call.argumentsJson() == null || call.argumentsJson().isBlank() ? "{}" : call.argumentsJson());
        } catch (RuntimeException e) {
            args = null;
        }
        if (args == null) {
            outcome = rejected("invalid_arguments");
        } else if (available(ctx).stream().noneMatch(t -> t.name().equals(call.name()))) {
            outcome = rejected("tool_not_available");
        } else {
            try {
                outcome = switch (call.name()) {
                    case REGISTER_INTENT -> registerIntent(ctx, args);
                    case DEBT_SUMMARY -> debtSummary(ctx);
                    case SIMULATE -> simulate(ctx, args);
                    case PROPOSE -> propose(ctx, args);
                    case ACCEPT -> accept(ctx, args);
                    case PROMISE -> promise(ctx, args);
                    case CALLBACK -> callback(ctx, args);
                    case TRANSFER -> transfer(args);
                    case PAYMENT -> payment(ctx);
                    default -> rejected("unknown_tool");
                };
            } catch (DomainException e) {
                outcome = new ToolOutcome(ToolCallOutcome.ERROR, error(e.code()), e.code(), null, null, null);
            } catch (ArgumentException e) {
                outcome = rejected(e.getMessage());
            }
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        ctx.toolCalls.add(new TurnContext.ToolCallLog(ctx.nextToolSequence(), call.name(), sanitizeArgs(call.name(), args),
                outcome.resultJson(), outcome.outcome(), outcome.errorCode(), outcome.referenceType(),
                outcome.referenceId(), ms));
        metrics.tool(call.name(), outcome.outcome().name());
        return outcome;
    }

    private ToolOutcome registerIntent(TurnContext ctx, Map<String, Object> args) {
        String intent = text(args.get("intent"));
        intent = intent == null ? "OTHER" : intent.toUpperCase(Locale.ROOT);
        if (!INTENTS.contains(intent)) {
            intent = "OTHER";
        }
        BigDecimal confidence = decimal(args.get("confidence"), new BigDecimal("0.5"));
        if (confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new ArgumentException("confidence_out_of_range");
        }
        ctx.intent = intent;
        ctx.confidence = confidence;
        ctx.memory = ctx.memory.withIntent(intent);
        List<String> signals = strings(args.get("signals"));
        for (String signal : signals) {
            HandoffReason reason = HandoffReason.parse(signal).filter(HandoffReason::selectableByModel).orElse(null);
            if (reason == null) {
                continue;
            }
            if (ctx.mode.drafts()) {
                ctx.alert = reason.name();
                continue;
            }
            return terminal(ok(Map.of("recorded", true)), new Terminal(TurnOutcome.HANDED_OFF, reason, TriggerSource.MODEL,
                    "sinal do modelo: " + reason.name(), null, null, false, false));
        }
        if (ctx.mode.autonomous() && confidence.compareTo(ctx.policy.settings().minConfidence()) < 0) {
            int failures = ctx.session.misunderstood();
            if (failures >= ctx.policy.settings().maxMisunderstandings()) {
                return terminal(ok(Map.of("recorded", true)), new Terminal(TurnOutcome.HANDED_OFF,
                        HandoffReason.LOW_CONFIDENCE, TriggerSource.MODEL, failures + " falhas seguidas de entendimento",
                        null, null, false, false));
            }
            return ok(Map.of("recorded", true, "understood", false, "instruction",
                    "Peça ao devedor, com educação, para explicar de outra forma."));
        }
        ctx.session.understood();
        return ok(Map.of("recorded", true, "understood", true));
    }

    private ToolOutcome debtSummary(TurnContext ctx) {
        SimulationView sim = actors.as(ctx.actor, () -> simulations.simulate(new SimulateCommand(
                ctx.conversation.creditorId(), ctx.conversation.debtorId(), titleIds(ctx), null, 0, null,
                List.of(new PlanInput(1, null, null)), ctx.conversation.channel())));
        var composition = sim.result().composition();
        ctx.memory = ctx.memory.withDebt(new SessionMemory.DebtSummary(composition.totalAmount(),
                composition.titles().size(), composition.maxDaysOverdue(), composition.referenceDate()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updatedTotal", composition.totalAmount());
        result.put("titles", composition.titles().size());
        result.put("daysOverdue", composition.maxDaysOverdue());
        result.put("referenceDate", composition.referenceDate().toString());
        ctx.memory = ctx.memory.withFacts(ctx.memory.facts().withDates(List.of(composition.referenceDate())));
        return new ToolOutcome(ToolCallOutcome.OK, codec.toJson(result), null, "simulation", sim.id(), null);
    }

    private ToolOutcome simulate(TurnContext ctx, Map<String, Object> args) {
        List<Integer> plans = integers(args.get("installments"));
        if (plans.isEmpty()) {
            plans = List.of(1);
        }
        if (plans.size() > 3 || plans.stream().anyMatch(n -> n < 1 || n > MAX_INSTALLMENTS)) {
            throw new ArgumentException("installments_invalid");
        }
        int level = integer(args.get("level"), 0);
        if (level < 0 || level > 20) {
            throw new ArgumentException("level_invalid");
        }
        String reason = text(args.get("reason"));
        JustificationInput justification = null;
        if (level > 0) {
            if (reason == null || !CONCESSION_REASONS.contains(reason.toUpperCase(Locale.ROOT))) {
                throw new ArgumentException("concession_reason_required");
            }
            justification = new JustificationInput(reason.toUpperCase(Locale.ROOT), null);
        }
        List<PlanInput> planInputs = plans.stream().distinct().map(n -> new PlanInput(n, null, null)).toList();
        JustificationInput just = justification;
        SimulationView sim = actors.as(ctx.actor, () -> simulations.simulate(new SimulateCommand(
                ctx.conversation.creditorId(), ctx.conversation.debtorId(), titleIds(ctx), null, level, just, planInputs,
                ctx.conversation.channel())));
        List<OfferView> valid = sim.result().offers().stream().filter(OfferView::valid)
                .sorted(Comparator.comparingInt(OfferView::level).thenComparingInt(OfferView::installments)).toList();
        List<OfferMemo> memos = new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        AllowedFacts facts = ctx.memory.facts();
        int i = 0;
        for (OfferView o : valid) {
            String ref = "O" + (++i);
            BigDecimal installment = o.schedule().size() > 1 ? o.schedule().get(1).amount() : o.schedule().getFirst().amount();
            LocalDate firstDue = o.schedule().getFirst().dueDate();
            memos.add(new OfferMemo(ref, sim.id(), o.level(), o.installments(), o.total(), o.downPayment(), installment,
                    firstDue, o.discountTotal(), rates(o.discountRates()), o.valid(), o.withinAuthority()));
            facts = facts.withAmounts(o.schedule().stream().map(PaymentView::amount).toList())
                    .withDates(o.schedule().stream().map(PaymentView::dueDate).toList());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ref", ref);
            m.put("level", o.level());
            m.put("installments", o.installments());
            m.put("total", o.total());
            m.put("downPayment", o.downPayment());
            m.put("installmentAmount", installment);
            m.put("firstDueDate", firstDue.toString());
            m.put("discountTotal", o.discountTotal());
            m.put("withinAuthority", o.withinAuthority());
            out.add(m);
        }
        ctx.memory = ctx.memory.withFacts(facts).withOffers(sim.id(), memos);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("grantedLevel", sim.result().grantedLevel());
        result.put("levelCapped", sim.result().levelCapped());
        result.put("nextLevelAvailable", sim.result().nextLevelAvailable());
        result.put("offers", out);
        if (out.isEmpty()) {
            result.put("note", "Nenhuma oferta válida para as formas pedidas; ofereça outra forma ou transfira para humano.");
        }
        return new ToolOutcome(ToolCallOutcome.OK, codec.toJson(result), null, "simulation", sim.id(), null);
    }

    private ToolOutcome propose(TurnContext ctx, Map<String, Object> args) {
        OfferMemo offer = ctx.memory.offer(text(args.get("offer"))).orElseThrow(() -> new ArgumentException("unknown_offer"));
        if (!offer.valid()) {
            throw new ArgumentException("offer_not_valid");
        }
        CreateProposalCommand command = new CreateProposalCommand(offer.simulationId(), offer.level(), null,
                new PlanInput(offer.installments(), null, null), null, null, false, ctx.conversation.channel());
        if (!offer.withinAuthority()) {
            // acima da alçada: o devedor é avisado antes, porque a transferência é feita pelo evento
            // HandoffRequested que o negotiation publica ao registrar a proposta HANDED_OFF
            boolean notified = channel.send(ctx, ctx.policy.settings().handoffMessage()).map(m -> {
                ctx.outboundMessageId = m.id();
                return !"FAILED".equals(m.status());
            }).orElse(false);
            try {
                ProposalView view = actors.as(ctx.actor, () -> proposals.create(command));
                remember(ctx, view);
                boolean handedOff = "HANDED_OFF".equals(view.status());
                return terminal(new ToolOutcome(ToolCallOutcome.OK, codec.toJson(proposalResult(ctx, view)), null,
                                "proposal", view.id(), null),
                        new Terminal(TurnOutcome.HANDED_OFF, HandoffReason.REQUIRES_APPROVAL, TriggerSource.ENGINE,
                                "proposta acima da alçada do assistente", "proposal", view.id(), handedOff, notified));
            } catch (DomainException e) {
                return terminal(new ToolOutcome(ToolCallOutcome.ERROR, error(e.code()), e.code(), null, null, null),
                        new Terminal(TurnOutcome.HANDED_OFF, HandoffReason.REQUIRES_APPROVAL, TriggerSource.ENGINE,
                                "condição acima da alçada; proposta não registrada (" + e.code() + ")", null, null, false,
                                notified));
            }
        }
        ProposalView view = actors.as(ctx.actor, () -> proposals.create(command));
        remember(ctx, view);
        if ("HANDED_OFF".equals(view.status())) {
            return terminal(new ToolOutcome(ToolCallOutcome.OK, codec.toJson(proposalResult(ctx, view)), null, "proposal",
                    view.id(), null), new Terminal(TurnOutcome.HANDED_OFF, HandoffReason.REQUIRES_APPROVAL,
                    TriggerSource.ENGINE, "proposta acima da alçada do assistente", "proposal", view.id(), true, false));
        }
        return new ToolOutcome(ToolCallOutcome.OK, codec.toJson(proposalResult(ctx, view)), null, "proposal", view.id(),
                null);
    }

    private ToolOutcome accept(TurnContext ctx, Map<String, Object> args) {
        ProposalMemo memo = ctx.memory.proposal();
        String ref = text(args.get("proposal"));
        if (memo == null || ref == null || !memo.ref().equalsIgnoreCase(ref.trim())) {
            throw new ArgumentException("unknown_proposal");
        }
        if (!"DRAFT".equals(memo.status()) && !"APPROVED".equals(memo.status())) {
            throw new ArgumentException("proposal_not_acceptable");
        }
        if (!HandoffDetector.explicitAcceptance(ctx.inputText)) {
            throw new ArgumentException("explicit_acceptance_required");
        }
        String reference = ctx.triggerMessageId == null ? null : "message:" + ctx.triggerMessageId;
        ProposalView accepted = actors.as(ctx.actor, () -> proposals.accept(memo.proposalId(),
                new AcceptCommand(acceptanceChannel(ctx.conversation.channel()), reference, null, null)));
        remember(ctx, accepted);
        boolean formalizes = ctx.mode == br.com.gestorcobranca.assistant.domain.model.AssistantMode.AUTONOMOUS
                && actors.as(ctx.actor, () -> authority.mine(ctx.conversation.creditorId())).profile().canFormalize();
        if (!formalizes) {
            Map<String, Object> result = new LinkedHashMap<>(proposalResult(ctx, accepted));
            result.put("next", "aguardando formalização por um atendente");
            return terminal(new ToolOutcome(ToolCallOutcome.OK, codec.toJson(result), null, "proposal", accepted.id(), null),
                    new Terminal(TurnOutcome.APPROVAL_REQUESTED, HandoffReason.FORMALIZATION_APPROVAL, TriggerSource.ENGINE,
                            "aceite registrado; formalização por humano", "proposal", accepted.id(), false, false));
        }
        AgreementView agreement = actors.as(ctx.actor, () -> proposals.formalize(memo.proposalId()));
        ctx.agreementId = agreement.id();
        ctx.memory = ctx.memory.withProposal(new ProposalMemo(memo.ref(), memo.proposalId(), "FORMALIZED",
                agreement.installments().size(), agreement.totalAmount(), agreement.id()));
        ctx.memory = ctx.memory.withFacts(ctx.memory.facts()
                .withAmounts(Stream.concat(agreement.installments().stream().map(i -> i.amount()),
                        Stream.of(agreement.totalAmount(), agreement.downPayment())).filter(java.util.Objects::nonNull).toList())
                .withDates(agreement.installments().stream().map(i -> i.dueDate()).toList())
                .withInstallments(List.of(agreement.installments().size())));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agreement", "A1");
        result.put("status", agreement.status());
        result.put("total", agreement.totalAmount());
        result.put("installments", agreement.installments().stream().map(i -> Map.of("number", i.number(),
                "dueDate", i.dueDate().toString(), "amount", i.amount())).toList());
        return new ToolOutcome(ToolCallOutcome.OK, codec.toJson(result), null, "agreement", agreement.id(), null);
    }

    private ToolOutcome promise(TurnContext ctx, Map<String, Object> args) {
        BigDecimal amount = decimal(args.get("amount"), null);
        if (amount == null || amount.signum() <= 0 || !ctx.memory.facts().allowsAmount(amount)) {
            throw new ArgumentException("amount_not_from_engine");
        }
        LocalDate date = date(args.get("date"));
        if (date.isBefore(today())) {
            throw new ArgumentException("date_in_past");
        }
        var result = actors.as(ctx.actor, () -> interactions.record(ctx.conversation.caseId(), new RecordInteractionCommand(
                ctx.conversation.channel(), "INBOUND", null, "PROMISE", null, null, null, null,
                new PromiseInput(amount, date), null, null, "ASSISTANT", externalRef(ctx, "promise"))));
        ctx.memory = ctx.memory.withFacts(ctx.memory.facts().withDates(List.of(date)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("registered", !result.duplicate());
        out.put("amount", amount);
        out.put("dueDate", date.toString());
        if (result.promise() != null) {
            out.put("status", result.promise().status());
        }
        return new ToolOutcome(ToolCallOutcome.OK, codec.toJson(out), null, "promise",
                result.promise() == null ? null : result.promise().id(), null);
    }

    private ToolOutcome callback(TurnContext ctx, Map<String, Object> args) {
        String raw = text(args.get("dateTime"));
        LocalDateTime dueAt;
        try {
            dueAt = LocalDateTime.parse(raw == null ? "" : raw.trim());
        } catch (DateTimeParseException e) {
            throw new ArgumentException("date_time_invalid");
        }
        LocalDateTime now = LocalDateTime.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        if (!dueAt.isAfter(now) || dueAt.isAfter(now.plusDays(CALLBACK_MAX_DAYS))) {
            throw new ArgumentException("date_time_out_of_range");
        }
        var result = actors.as(ctx.actor, () -> interactions.record(ctx.conversation.caseId(), new RecordInteractionCommand(
                ctx.conversation.channel(), "INBOUND", null, "CALLBACK", null, null, null, null, null,
                new CallbackInput(dueAt, null, "Retorno combinado com o assistente virtual"), null, "ASSISTANT",
                externalRef(ctx, "callback"))));
        ctx.memory = ctx.memory.withFacts(ctx.memory.facts().withDates(List.of(dueAt.toLocalDate())));
        return new ToolOutcome(ToolCallOutcome.OK, codec.toJson(Map.of("scheduled", true, "dateTime", dueAt.toString())),
                null, "task", result.callback() == null ? null : result.callback().id(), null);
    }

    private ToolOutcome transfer(Map<String, Object> args) {
        HandoffReason reason = HandoffReason.parse(text(args.get("reason"))).filter(HandoffReason::selectableByModel)
                .orElse(HandoffReason.MODEL_REQUESTED);
        String summary = text(args.get("summary"));
        String note = summary == null ? null : PersonalDataRedactor.redact(summary, true);
        if (note != null && note.length() > 500) {
            note = note.substring(0, 500);
        }
        return terminal(ok(Map.of("transferring", true)), new Terminal(TurnOutcome.HANDED_OFF, reason, TriggerSource.MODEL,
                note, null, null, false, false));
    }

    private ToolOutcome payment(TurnContext ctx) {
        PaymentInstructionPort port = payments.getIfAvailable();
        ProposalMemo memo = ctx.memory.proposal();
        if (port == null || memo == null || memo.agreementId() == null) {
            throw new ArgumentException("payment_not_available");
        }
        PaymentInstructionPort.PixInstruction pix = actors.as(ctx.actor, () -> port.createPix(memo.agreementId(), null));
        // o código Pix vai ao devedor em mensagem própria; o modelo só recebe valor e vencimento
        channel.send(ctx, "Pix para pagamento: " + pix.copyPaste());
        ctx.memory = ctx.memory.withFacts(ctx.memory.facts().withAmounts(List.of(pix.amount()))
                .withDates(pix.dueDate() == null ? List.of() : List.of(pix.dueDate())));
        return new ToolOutcome(ToolCallOutcome.OK, codec.toJson(Map.of("sent", true, "amount", pix.amount(),
                "dueDate", String.valueOf(pix.dueDate()))), null, "payment_instruction", pix.instructionId(), null);
    }

    // ------------------------------------------------------------------ apoio

    private void remember(TurnContext ctx, ProposalView view) {
        ctx.proposalId = view.id();
        String ref = ctx.memory.proposal() != null && ctx.memory.proposal().proposalId().equals(view.id())
                ? ctx.memory.proposal().ref() : "P" + (ctx.memory.proposal() == null ? 1
                : Integer.parseInt(ctx.memory.proposal().ref().substring(1)) + 1);
        ctx.memory = ctx.memory.withProposal(new ProposalMemo(ref, view.id(), view.status(), view.installments(),
                view.totalAmount(), view.agreementId()));
        if (view.offer() != null) {
            ctx.memory = ctx.memory.withFacts(ctx.memory.facts()
                    .withAmounts(view.offer().schedule().stream().map(PaymentView::amount).toList())
                    .withDates(view.offer().schedule().stream().map(PaymentView::dueDate).toList()));
        }
        if (view.validUntil() != null) {
            ctx.memory = ctx.memory.withFacts(ctx.memory.facts().withDates(List.of(view.validUntil())));
        }
    }

    private Map<String, Object> proposalResult(TurnContext ctx, ProposalView view) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("proposal", ctx.memory.proposal().ref());
        m.put("status", view.status());
        m.put("installments", view.installments());
        m.put("total", view.totalAmount());
        m.put("firstDueDate", view.firstDueDate() == null ? null : view.firstDueDate().toString());
        m.put("validUntil", view.validUntil() == null ? null : view.validUntil().toString());
        return m;
    }

    /** Títulos em aberto do devedor no credor da conversa (e na carteira do caso, quando houver). */
    private List<UUID> titleIds(TurnContext ctx) {
        UUID portfolio = ctx.conversation.portfolioId();
        return actors.as(ctx.actor, () -> titles.listByDebtor(ctx.conversation.debtorId())).stream()
                .filter(t -> ctx.conversation.creditorId().equals(t.creditorId()))
                .filter(t -> portfolio == null || portfolio.equals(t.portfolioId()))
                .filter(t -> "OPEN".equals(t.status()))
                .map(DebtTitleUseCases.TitleView::id)
                .toList();
    }

    private static List<BigDecimal> rates(ComponentsView c) {
        if (c == null) {
            return List.of();
        }
        return Stream.of(c.principal(), c.correction(), c.interest(), c.fine(), c.fees(), c.honorarium())
                .map(v -> v == null ? BigDecimal.ZERO : v).toList();
    }

    private static String acceptanceChannel(String conversationChannel) {
        return switch (conversationChannel == null ? "" : conversationChannel) {
            case "WHATSAPP", "EMAIL", "SMS" -> conversationChannel;
            default -> "CHAT";
        };
    }

    private static String externalRef(TurnContext ctx, String kind) {
        return "assistant:" + (ctx.triggerMessageId == null ? ctx.token : ctx.triggerMessageId) + ":" + kind;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }

    private ToolOutcome ok(Map<String, Object> result) {
        return new ToolOutcome(ToolCallOutcome.OK, codec.toJson(result), null, null, null, null);
    }

    private ToolOutcome rejected(String code) {
        return new ToolOutcome(ToolCallOutcome.REJECTED, error(code), code, null, null, null);
    }

    private static ToolOutcome terminal(ToolOutcome base, Terminal terminal) {
        return new ToolOutcome(base.outcome(), base.resultJson(), base.errorCode(), base.referenceType(),
                base.referenceId(), terminal);
    }

    private String error(String code) {
        return codec.toJson(Map.of("error", code == null ? "error" : code));
    }

    /** Argumentos registrados na trilha: só os campos conhecidos (o resumo livre do modelo é minimizado). */
    private String sanitizeArgs(String tool, Map<String, Object> args) {
        if (args == null) {
            return "{}";
        }
        Map<String, Object> kept = new LinkedHashMap<>();
        for (String key : List.of("intent", "confidence", "signals", "installments", "level", "reason", "offer",
                "proposal", "amount", "date", "dateTime")) {
            if (args.containsKey(key)) {
                kept.put(key, args.get(key));
            }
        }
        if (TRANSFER.equals(tool) && args.get("summary") != null) {
            kept.put("summary", PersonalDataRedactor.redact(String.valueOf(args.get("summary")), true));
        }
        return codec.toJson(kept);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal decimal(Object value, BigDecimal fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new ArgumentException("number_invalid");
        }
    }

    private static int integer(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim()).intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new ArgumentException("integer_invalid");
        }
    }

    private static List<Integer> integers(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            return List.of(integer(value, 1));
        }
        return list.stream().map(v -> integer(v, 1)).toList();
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return value == null ? List.of() : List.of(String.valueOf(value));
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static LocalDate date(Object value) {
        try {
            return LocalDate.parse(String.valueOf(value).trim());
        } catch (DateTimeParseException e) {
            throw new ArgumentException("date_invalid");
        }
    }

    /** Argumento inválido pedido pelo modelo (recusado sem chamar o módulo dono). */
    static final class ArgumentException extends RuntimeException {

        ArgumentException(String code) {
            super(code);
        }
    }
}
