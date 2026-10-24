package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.assistant.application.port.out.PaymentInstructionPort;
import br.com.gestorcobranca.assistant.application.port.out.PaymentInstructionPort.IssuePixCommand;
import br.com.gestorcobranca.assistant.application.port.out.PaymentInstructionPort.PixInstruction;
import br.com.gestorcobranca.assistant.application.service.AssistantToolbox.ArgumentException;
import br.com.gestorcobranca.assistant.application.service.AssistantToolbox.Terminal;
import br.com.gestorcobranca.assistant.application.service.AssistantToolbox.ToolOutcome;
import br.com.gestorcobranca.assistant.domain.model.HandoffReason;
import br.com.gestorcobranca.assistant.domain.model.PixDecision;
import br.com.gestorcobranca.assistant.domain.model.PixRequest;
import br.com.gestorcobranca.assistant.domain.model.PolicySettings.PixInConversation;
import br.com.gestorcobranca.assistant.domain.model.SessionMemory.InstallmentMemo;
import br.com.gestorcobranca.assistant.domain.model.ToolCallOutcome;
import br.com.gestorcobranca.assistant.domain.model.TriggerSource;
import br.com.gestorcobranca.assistant.domain.model.TurnOutcome;
import br.com.gestorcobranca.assistant.domain.repository.AssistantTurnRepository;
import br.com.gestorcobranca.assistant.domain.repository.PixRequestRepository;
import br.com.gestorcobranca.assistant.domain.service.HandoffDetector;
import br.com.gestorcobranca.assistant.domain.service.PixEligibility;
import br.com.gestorcobranca.assistant.domain.service.PixEligibility.Evaluation;
import br.com.gestorcobranca.assistant.domain.service.PixMessage;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementFilter;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.InstallmentView;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Pix na conversa (ADR-0009; docs/tecnico/desenho/assistente.md §5.1): ferramentas
 * {@code get_installments} (parcelas em aberto de acordos vigentes do devedor, com referência opaca) e
 * {@code generate_payment} (avaliação dos critérios no servidor — {@link PixEligibility} — e, se atendidos,
 * Pix imediato da parcela pelo payment; nos modos sombra/copiloto só a sugestão ao operador). O código não
 * vai ao modelo nem sai no turno: a entrega é feita por {@link AssistantPixDelivery} quando o turno respondeu
 * ao devedor e a cobrança está ativa no PSP. Se o turno terminar sem resposta (guardrail, transferência,
 * porteiro), a instrução criada no turno é cancelada ({@link #settle}).
 */
@Component
class AssistantPix {

    private static final Logger LOG = LoggerFactory.getLogger(AssistantPix.class);
    private static final Set<String> OPEN_DISPUTE = Set.of("OPEN", "UNDER_REVIEW");
    private static final int MAX_INSTALLMENTS_LISTED = 6;
    /** Instrução reutilizada com menos que isto de validade é substituída por uma nova. */
    private static final Duration MIN_REMAINING_VALIDITY = Duration.ofMinutes(10);
    static final String DELIVERY_NOTE = "O código Pix será enviado em seguida, em mensagem separada.";

    private final AgreementUseCases agreements;
    private final DisputeUseCases disputes;
    private final ObjectProvider<PaymentInstructionPort> payments;
    private final AssistantTurnRepository turns;
    private final PixRequestRepository requests;
    private final AssistantActors actors;
    private final AssistantCodec codec;
    private final AssistantMetrics metrics;
    private final Clock clock;

    AssistantPix(AgreementUseCases agreements, DisputeUseCases disputes, ObjectProvider<PaymentInstructionPort> payments,
                 AssistantTurnRepository turns, PixRequestRepository requests, AssistantActors actors,
                 AssistantCodec codec, AssistantMetrics metrics, Clock clock) {
        this.agreements = agreements;
        this.disputes = disputes;
        this.payments = payments;
        this.turns = turns;
        this.requests = requests;
        this.actors = actors;
        this.codec = codec;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** As ferramentas de Pix são oferecidas: política ligada e (modos autônomos) adaptador do payment presente. */
    boolean offered(TurnContext ctx) {
        if (!ctx.hasPolicy() || !ctx.policy.settings().pix().on()) {
            return false;
        }
        return ctx.mode.drafts() || (ctx.mode.autonomous() && payments.getIfAvailable() != null);
    }

    // ------------------------------------------------------------------ get_installments

    ToolOutcome installments(TurnContext ctx) {
        UUID portfolio = ctx.conversation.portfolioId();
        List<AgreementView> active = actors.as(ctx.actor, () -> agreements.search(new AgreementFilter(
                ctx.conversation.creditorId(), ctx.conversation.debtorId(), null, null), PageQuery.of(0, 50)).content())
                .stream()
                .filter(a -> PixEligibility.ACTIVE_AGREEMENT.contains(a.status()))
                .filter(a -> ctx.conversation.debtorId().equals(a.debtorId())
                        && ctx.conversation.creditorId().equals(a.creditorId()))
                .filter(a -> portfolio == null || portfolio.equals(a.portfolioId()))
                .sorted(Comparator.comparing(AgreementView::formalizedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<InstallmentMemo> memos = new ArrayList<>();
        int i = 0;
        for (AgreementView a : active) {
            for (InstallmentView inst : a.installments().stream()
                    .filter(x -> PixEligibility.OPEN_INSTALLMENT.contains(x.status()))
                    .sorted(Comparator.comparingInt(InstallmentView::number)).toList()) {
                if (memos.size() >= MAX_INSTALLMENTS_LISTED) {
                    break;
                }
                memos.add(new InstallmentMemo("I" + (++i), a.id(), inst.id(), inst.number(), inst.dueDate(),
                        remaining(inst), inst.status(), a.origin()));
            }
        }
        ctx.memory = ctx.memory.withInstallments(memos);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("installments", memos.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ref", m.ref());
            row.put("number", m.number());
            row.put("dueDate", m.dueDate().toString());
            row.put("amount", m.amount());
            row.put("status", m.status());
            return row;
        }).toList());
        if (memos.isEmpty()) {
            result.put("note", "Nenhuma parcela em aberto de acordo vigente nesta conversa.");
        }
        return new ToolOutcome(ToolCallOutcome.OK, codec.toJson(result), null, null, null, null);
    }

    // ------------------------------------------------------------------ generate_payment

    ToolOutcome request(TurnContext ctx, String ref) {
        InstallmentMemo memo = ctx.memory.installment(ref).orElseThrow(() -> new ArgumentException("unknown_installment"));
        if (ctx.pixRequests.stream().anyMatch(r -> r.getDecision() != PixDecision.DENIED)) {
            throw new ArgumentException("pix_already_requested_in_turn");
        }
        PixInConversation settings = ctx.policy.settings().pix();
        Instant now = Instant.now(clock);
        AgreementView agreement = actors.as(ctx.actor, () -> agreements.get(memo.agreementId()));
        InstallmentView installment = agreement.installments().stream()
                .filter(x -> x.id().equals(memo.installmentId())).findFirst().orElse(null);
        BigDecimal amount = installment == null ? null : remaining(installment);
        boolean sameParties = ctx.conversation.debtorId().equals(agreement.debtorId())
                && ctx.conversation.creditorId().equals(agreement.creditorId())
                && (ctx.conversation.portfolioId() == null || ctx.conversation.portfolioId().equals(agreement.portfolioId()));
        Instant since = now.minus(Duration.ofDays(settings.riskLookbackDays()));
        Set<String> risks = new LinkedHashSet<>();
        turns.handoffReasonsSince(ctx.session.getId(), since).stream()
                .filter(r -> HandoffReason.parse(r).map(HandoffReason::conversationRisk).orElse(false))
                .forEach(risks::add);
        HandoffDetector.detect(ctx.inputText, ctx.policy.settings().extraTriggers()).forEach(d -> risks.add(d.reason().name()));
        if (ctx.alert != null) {
            risks.add(ctx.alert);
        }
        boolean guardrail = !ctx.guardrails.isEmpty() || turns.countGuardrailsSince(ctx.session.getId(), since) > 0;
        boolean dispute = openDispute(ctx, agreement);
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        long issuedToday = requests.countIssuedSince(ctx.conversation.id(), today.atStartOfDay(TimeConfig.BUSINESS_ZONE)
                .toInstant());
        Evaluation evaluation = PixEligibility.evaluate(new PixEligibility.Facts(settings, ctx.mode,
                ctx.session.getIdentityStatus(), installment == null ? null : new PixEligibility.Agreement(
                agreement.status(), agreement.origin(), installment.status(), amount), sameParties, risks, guardrail,
                dispute, HandoffDetector.explicitPaymentRequest(ctx.inputText), (int) issuedToday));
        PixRequest.Target target = installment == null ? null : new PixRequest.Target(agreement.id(), installment.id(),
                installment.number(), kind(agreement, installment).name(), amount);
        if (!evaluation.allowed()) {
            return deny(ctx, memo, target, evaluation, evaluation.denialCode().orElseThrow(),
                    evaluation.denial().orElseThrow().handsOff(), null, now);
        }
        if (evaluation.decision() == PixDecision.SUGGESTED) {
            return suggest(ctx, memo, target, evaluation, now);
        }
        return issue(ctx, memo, target, evaluation, settings, now);
    }

    private ToolOutcome issue(TurnContext ctx, InstallmentMemo memo, PixRequest.Target target, Evaluation evaluation,
                              PixInConversation settings, Instant now) {
        PaymentInstructionPort port = payments.getObject();
        int seconds = settings.expirationMinutes() * 60;
        String key = "assistant:pix:" + ctx.token + ":" + target.installmentId();
        PixInstruction pix;
        try {
            pix = actors.as(ctx.actor, () -> port.issueImmediatePix(new IssuePixCommand(target.agreementId(),
                    target.installmentId(), target.amount(), seconds, ctx.conversation.channel(), key)));
            if (pix.reused() && (!pix.open() || pix.expiresAt().isBefore(now.plus(MIN_REMAINING_VALIDITY)))) {
                // instrução aberta reutilizada perto de expirar: o devedor recebe uma com a validade da política
                PixInstruction stale = pix;
                actors.as(ctx.actor, () -> {
                    port.cancel(stale.instructionId(), "Substituída pelo assistente virtual (validade insuficiente)");
                    return null;
                });
                pix = actors.as(ctx.actor, () -> port.issueImmediatePix(new IssuePixCommand(target.agreementId(),
                        target.installmentId(), target.amount(), seconds, ctx.conversation.channel(), key + ":renew")));
            }
        } catch (DomainException e) {
            LOG.atWarn().addKeyValue("conversationId", ctx.conversation.id()).addKeyValue("code", e.code())
                    .log("Pix do assistente recusado pelo payment");
            return deny(ctx, memo, target, evaluation, "PAYMENT_UNAVAILABLE", true, e.code(), now);
        }
        if (!pix.open()) {
            return deny(ctx, memo, target, evaluation, "PAYMENT_UNAVAILABLE", true, "instruction_" + pix.status(), now);
        }
        PixRequest request = new PixRequest(scope(ctx), PixDecision.ISSUED, null, criteria(evaluation), target,
                new PixRequest.Instruction(pix.instructionId(), pix.reused(), pix.expiresAt()), now);
        ctx.pixRequests.add(request);
        LocalDate expiryDate = pix.expiresAt().atZone(TimeConfig.BUSINESS_ZONE).toLocalDate();
        ctx.memory = ctx.memory.withFacts(ctx.memory.facts().withAmounts(List.of(target.amount()))
                .withDates(List.of(expiryDate)).withInstallments(List.of(target.installmentNumber())));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issued", true);
        result.put("installment", memo.ref());
        result.put("number", target.installmentNumber());
        result.put("amount", target.amount());
        result.put("validUntil", pix.expiresAt().atZone(TimeConfig.BUSINESS_ZONE).toLocalDateTime().toString());
        result.put("validUntilDate", expiryDate.toString());
        result.put("delivery", DELIVERY_NOTE);
        result.put("criteria", criteriaSummary(evaluation));
        return new ToolOutcome(ToolCallOutcome.OK, codec.toJson(result), null, "pix_request", request.getId(), null);
    }

    private ToolOutcome suggest(TurnContext ctx, InstallmentMemo memo, PixRequest.Target target, Evaluation evaluation,
                                Instant now) {
        PixRequest request = new PixRequest(scope(ctx), PixDecision.SUGGESTED, null, criteria(evaluation), target, null, now);
        ctx.pixRequests.add(request);
        ctx.pixSuggestion = "Pix elegível: gere pela tela do acordo (" + label(target) + ", R$ "
                + target.amount().toPlainString() + ") — o assistente não envia nos modos sombra/copiloto.";
        ctx.memory = ctx.memory.withFacts(ctx.memory.facts().withAmounts(List.of(target.amount())));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("suggested", true);
        result.put("installment", memo.ref());
        result.put("amount", target.amount());
        result.put("note", "Modo de rascunho: o atendente gera e envia o Pix pela tela.");
        result.put("criteria", criteriaSummary(evaluation));
        return new ToolOutcome(ToolCallOutcome.OK, codec.toJson(result), null, "pix_request", request.getId(), null);
    }

    /**
     * Recusa: registrada com o motivo; recusas em que um atendente pode concluir o pagamento transferem
     * (modos autônomos) com {@link HandoffReason#PAYMENT_ASSISTANCE}.
     */
    private ToolOutcome deny(TurnContext ctx, InstallmentMemo memo, PixRequest.Target target, Evaluation evaluation,
                             String code, boolean handsOff, String errorCode, Instant now) {
        PixRequest request = new PixRequest(scope(ctx), PixDecision.DENIED, code, criteria(evaluation), target, null, now);
        ctx.pixRequests.add(request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issued", false);
        result.put("installment", memo.ref());
        result.put("reason", code);
        result.put("criteria", criteriaSummary(evaluation));
        boolean transfer = handsOff && ctx.mode.autonomous();
        result.put("instruction", transfer ? "Um atendente vai continuar o atendimento para concluir o pagamento."
                : "Não gere o Pix agora. Se o devedor quiser pagar, peça que confirme explicitamente "
                + "(ex.: \"quero pagar agora\") ou diga que um atendente pode ajudar.");
        if (ctx.mode.drafts()) {
            ctx.pixSuggestion = "Pix não sugerido (" + code + ").";
        }
        ToolOutcome base = new ToolOutcome(ToolCallOutcome.OK, codec.toJson(result), errorCode, "pix_request",
                request.getId(), null);
        if (!transfer) {
            return base;
        }
        return new ToolOutcome(base.outcome(), base.resultJson(), base.errorCode(), base.referenceType(),
                base.referenceId(), new Terminal(TurnOutcome.HANDED_OFF, HandoffReason.PAYMENT_ASSISTANCE,
                TriggerSource.POLICY, "Pix não liberado ao assistente: " + code, "pix_request", request.getId(), false,
                false));
    }

    // ------------------------------------------------------------------ fim do turno

    /**
     * Fim do turno: sem resposta enviada ao devedor ({@code REPLIED}), a instrução gerada no turno é cancelada
     * no payment (uma reutilizada, criada antes por outro canal, é mantida) e nada é entregue.
     */
    void settle(TurnContext ctx) {
        if (ctx.outcome == TurnOutcome.REPLIED) {
            return;
        }
        for (PixRequest r : ctx.pixRequests) {
            if (r.getDecision() != PixDecision.ISSUED) {
                continue;
            }
            if (!r.isReusedInstruction()) {
                try {
                    actors.as(ctx.actor, () -> {
                        payments.getObject().cancel(r.getInstructionId(),
                                "Turno do assistente terminou sem resposta ao devedor");
                        return null;
                    });
                } catch (DomainException e) {
                    LOG.atWarn().addKeyValue("instructionId", r.getInstructionId()).addKeyValue("code", e.code())
                            .log("Instrução do Pix do assistente não cancelada");
                }
            }
            r.cancelDelivery("TURN_" + (ctx.outcome == null ? "SKIPPED" : ctx.outcome.name()));
        }
    }

    // ------------------------------------------------------------------ apoio

    private boolean openDispute(TurnContext ctx, AgreementView agreement) {
        Set<UUID> cases = new LinkedHashSet<>();
        Stream.of(agreement.caseId(), ctx.conversation.caseId()).filter(Objects::nonNull).forEach(cases::add);
        for (UUID caseId : cases) {
            boolean open = actors.as(ctx.actor, () -> disputes.listByCase(caseId)).stream()
                    .anyMatch(d -> OPEN_DISPUTE.contains(d.status()));
            if (open) {
                return true;
            }
        }
        return false;
    }

    private static PixRequest.Scope scope(TurnContext ctx) {
        return new PixRequest.Scope(ctx.session.getId(), ctx.conversation.id(), ctx.conversation.creditorId(),
                ctx.conversation.portfolioId(), ctx.conversation.debtorId(), ctx.conversation.caseId(),
                ctx.policy.policy().getId(), ctx.mode);
    }

    private String criteria(Evaluation evaluation) {
        return codec.toJson(evaluation.checks().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("criterion", c.criterion().name());
            m.put("passed", c.passed());
            m.put("detail", c.detail());
            return m;
        }).toList());
    }

    /** Ao modelo: só os códigos e o resultado (sem limites nem detalhes). */
    private static List<Map<String, Object>> criteriaSummary(Evaluation evaluation) {
        return evaluation.checks().stream().map(c -> Map.<String, Object>of("criterion", c.criterion().name(),
                "passed", c.passed())).toList();
    }

    static PixMessage.Kind kind(AgreementView a, InstallmentView i) {
        if (a.installments().size() == 1) {
            return PixMessage.Kind.SINGLE;
        }
        if (i.number() == 1 && a.downPayment() != null && a.downPayment().signum() > 0) {
            return PixMessage.Kind.DOWN_PAYMENT;
        }
        return PixMessage.Kind.INSTALLMENT;
    }

    private static String label(PixRequest.Target t) {
        return switch (PixMessage.Kind.valueOf(t.kind())) {
            case SINGLE -> "pagamento à vista";
            case DOWN_PAYMENT -> "entrada";
            case INSTALLMENT -> "parcela " + t.installmentNumber();
        };
    }

    private static BigDecimal remaining(InstallmentView i) {
        BigDecimal paid = i.paidAmount() == null ? BigDecimal.ZERO : i.paidAmount();
        return i.amount().subtract(paid).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_EVEN);
    }
}
