package br.com.gestorcobranca.sandbox.application.service;

import br.com.gestorcobranca.sandbox.domain.service.DemoPdfBytes;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.PromiseInput;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.RecordInteractionCommand;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.RecordResult;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.MessageView;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.SendMessageCommand;
import br.com.gestorcobranca.communication.application.port.in.SandboxUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.InstallmentView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.PlanInput;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.SimulateCommand;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.AcceptCommand;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.CreateProposalCommand;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.ProposalView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.EvidenceUpload;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ExternalPaymentCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.TargetInput;
import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import br.com.gestorcobranca.sandbox.domain.model.DemoHistory.HistoryCreditor;
import br.com.gestorcobranca.sandbox.domain.model.DemoPortfolioData.Debt;
import br.com.gestorcobranca.telephony.application.port.in.AgentUseCases;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.CallView;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.ClickToCallCommand;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.TabulateCommand;
import br.com.gestorcobranca.telephony.application.port.in.TelephonySandboxUseCases;
import br.com.gestorcobranca.shared.security.Actor;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Atos da operação do histórico fictício (decisão 1352), cada um pelo caso de uso público e pelo perfil que o faria:
 * contato registrado, acordo (simulação → proposta na alçada → aceite → formalização), pagamento informado pelo
 * negociador e aprovado pelo financeiro, mensagem por template, resposta do devedor e resposta escrita do negociador no
 * chat, e ligação pelo discador simulado com conversa e pós-atendimento. O relógio de demonstração já está na data do
 * fato; as datas explícitas (início do contato, data do pagamento) nunca passam de "agora".
 */
@SandboxSeedComponent
class HistoryOperations {

    private static final byte[] RECEIPT = DemoPdfBytes.of("Comprovante ficticio do historico do sandbox");
    private static final Set<String> CALL_LIVE = Set.of("IN_PROGRESS", "ANSWERED", "CONNECTED");
    /** Passo do relógio durante a ligação: menor que o prazo de presença do operador na telefonia (90 s). */
    private static final Duration CALL_STEP = Duration.ofSeconds(50);

    private final InteractionUseCases interactions;
    private final NegotiationSimulationUseCases simulations;
    private final ProposalUseCases proposals;
    private final AgreementUseCases agreements;
    private final PaymentUseCases payments;
    private final MessageUseCases messages;
    private final SandboxUseCases messagingSandbox;
    private final AgentUseCases agents;
    private final CallUseCases calls;
    private final TelephonySandboxUseCases telephonySandbox;
    private final SeedRuntime rt;

    HistoryOperations(InteractionUseCases interactions, NegotiationSimulationUseCases simulations,
                      ProposalUseCases proposals, AgreementUseCases agreements, PaymentUseCases payments,
                      MessageUseCases messages, SandboxUseCases messagingSandbox, AgentUseCases agents,
                      CallUseCases calls, TelephonySandboxUseCases telephonySandbox, SeedRuntime rt) {
        this.interactions = interactions;
        this.simulations = simulations;
        this.proposals = proposals;
        this.agreements = agreements;
        this.payments = payments;
        this.messages = messages;
        this.messagingSandbox = messagingSandbox;
        this.agents = agents;
        this.calls = calls;
        this.telephonySandbox = telephonySandbox;
        this.rt = rt;
    }

    /** Caso de cobrança de um devedor fictício de uma carteira do histórico. */
    static final class HistoryCase {
        final HistoryCreditor creditor;
        final String portfolioCode;
        final UUID creditorId;
        final UUID portfolioId;
        final UUID debtorId;
        final UUID caseId;
        final Debt debt;
        /** Acordo em vigor (não negocia de novo enquanto houver). */
        PlannedAgreement agreement;
        /** Até quando o acordo em vigor (quebrando ou cancelado) ainda impede outro acordo. */
        LocalDate busyUntil;
        boolean closed;
        UUID conversationId;
        /** Negociador responsável pelo caso (distribuição da fila); nulo = caso sem responsável. */
        String assigneeKey;
        /** Dono da conversa de WhatsApp (quem mandou a primeira mensagem). */
        String conversationOwner;

        HistoryCase(HistoryCreditor creditor, String portfolioCode, UUID creditorId, UUID portfolioId, UUID debtorId,
                    UUID caseId, Debt debt) {
            this.creditor = creditor;
            this.portfolioCode = portfolioCode;
            this.creditorId = creditorId;
            this.portfolioId = portfolioId;
            this.debtorId = debtorId;
            this.caseId = caseId;
            this.debt = debt;
        }

        String ref() {
            return debt.contract().number();
        }

        /** Livre para negociar: sem acordo em vigor (ou com o desfecho já alcançado). */
        boolean available(LocalDate today) {
            if (agreement != null && agreement.fate == Fate.KEEP) {
                return false;
            }
            return busyUntil == null || today.isAfter(busyUntil);
        }

        String firstName() {
            String first = debt.debtor().name().split(" ")[0];
            return first.replaceAll("[^\\p{L}]", "");
        }
    }

    /** Desfecho planejado do acordo (cumprido, quebrado depois de N parcelas, cancelado). */
    enum Fate { KEEP, BREAK, CANCEL }

    /** Acordo do histórico e o que falta pagar dele. */
    static final class PlannedAgreement {
        final UUID agreementId;
        final String closerKey;
        final HistoryCase target;
        final Fate fate;
        /** Parcelas que o devedor vai pagar (todas, no cumprido). */
        final int installmentsToPay;
        final List<InstallmentView> installments;
        int paid;
        final LocalDate formalizedOn;

        PlannedAgreement(UUID agreementId, String closerKey, HistoryCase target, Fate fate, int installmentsToPay,
                         List<InstallmentView> installments, LocalDate formalizedOn) {
            this.agreementId = agreementId;
            this.closerKey = closerKey;
            this.target = target;
            this.fate = fate;
            this.installmentsToPay = installmentsToPay;
            this.installments = List.copyOf(installments);
            this.formalizedOn = formalizedOn;
        }

        boolean finishedPaying() {
            return paid >= installmentsToPay;
        }
    }

    // ------------------------------------------------------------------------------------------------ contatos

    /**
     * Contato registrado pelo negociador (idempotente pela referência externa).
     *
     * @return a interação (nula quando já existia)
     */
    UUID interaction(TenantSeed seed, String key, HistoryCase c, String channel, String tabulation, String note,
                     Instant startedAt, Duration duration, PromiseInput promise, String externalRef) {
        RecordResult result = rt.as(seed.actor(key), () -> interactions.record(c.caseId, new RecordInteractionCommand(
                channel, "OUTBOUND", null, tabulation, note + " (histórico fictício)", null, startedAt,
                startedAt.plus(duration), promise, null, null, "MANUAL", externalRef, null)));
        return result.duplicate() ? null : result.interaction().id();
    }

    // ------------------------------------------------------------------------------------------------ acordos

    /** Simulação → proposta na alçada (nível 0) → aceite → formalização, pelo negociador. */
    AgreementView agreement(TenantSeed seed, String key, HistoryCase c, int installments, String channel,
                            String reference) {
        Actor operator = seed.actor(key);
        UUID simulation = rt.as(operator, () -> simulations.simulate(new SimulateCommand(c.creditorId, c.debtorId,
                List.of(), null, null, null, List.of(new PlanInput(installments, null, null)), channel))).id();
        ProposalView proposal = rt.as(operator, () -> proposals.create(new CreateProposalCommand(simulation, 0, null,
                new PlanInput(installments, null, null), null, null, false, channel)));
        if ("PENDING_APPROVAL".equals(proposal.status())) {
            // acima da alçada do negociador: a supervisão aprova antes do aceite (fila de aprovação)
            rt.as(seed.actor("supervisor"), () -> proposals.approve(proposal.id(),
                    "Dentro da política do credor; aprovado (histórico fictício)"));
        }
        rt.as(operator, () -> proposals.accept(proposal.id(), new AcceptCommand(channel, reference, null, null)));
        return rt.as(operator, () -> proposals.formalize(proposal.id()));
    }

    /** Cancelamento antes do primeiro pagamento (supervisão). */
    void cancel(TenantSeed seed, PlannedAgreement a) {
        rt.as(seed.actor("supervisor"), () -> agreements.cancel(a.agreementId,
                "Devedor desistiu antes do primeiro pagamento (histórico fictício)"));
    }

    AgreementView agreementView(TenantSeed seed, UUID agreementId) {
        return rt.as(seed.actor("financeiro"), () -> agreements.get(agreementId));
    }

    /**
     * Pagamento da parcela informado pelo negociador (transferência ou Pix na conta da assessoria, com comprovante) e,
     * quando cai na revisão por valor, aprovado pelo financeiro (segunda pessoa).
     */
    void pay(TenantSeed seed, PlannedAgreement a, InstallmentView installment, Instant paidAt, boolean pix) {
        PaymentView payment = rt.as(seed.actor(a.closerKey), () -> payments.registerExternal(new ExternalPaymentCommand(
                a.target.creditorId, a.target.debtorId, installment.amount(), paidAt, pix ? "PIX" : "TRANSFER",
                "AGENCY_ACCOUNT", "SBX-HIST-" + installment.id().toString().substring(0, 8).toUpperCase(),
                "Parcela " + installment.number() + " do acordo (histórico fictício)",
                new TargetInput("AGREEMENT", a.agreementId, List.of(), null)), receipt(),
                "sandbox-hist-pay-" + installment.id()));
        if ("INFORMED".equals(payment.status())) {
            rt.as(seed.actor("financeiro"), () -> payments.approve(payment.id(),
                    "Comprovante conferido com o extrato (histórico fictício)"));
        }
    }

    // ------------------------------------------------------------------------------------------------ mensagens

    MessageView sendTemplate(TenantSeed seed, String key, HistoryCase c, String channel, String template,
                             Map<String, String> variables, String idempotencyKey) {
        MessageView view = rt.as(seed.actor(key), () -> messages.send(new SendMessageCommand(null, c.debtorId,
                c.creditorId, c.caseId, channel, null, template, variables, null, null, idempotencyKey)));
        if ("WHATSAPP".equals(channel) && view.conversationId() != null) {
            c.conversationId = view.conversationId();
            if (c.conversationOwner == null) {
                c.conversationOwner = key;
            }
        }
        return view;
    }

    /** Resposta do devedor no WhatsApp simulado (chega pelo mesmo caminho do webhook do provedor). */
    void inbound(TenantSeed seed, HistoryCase c, String text) {
        rt.asRun(seed.actor("integracao"), () -> messagingSandbox.simulateInbound(seed.whatsappAccountId,
                c.debt.debtor().phone(), text));
    }

    /** Resposta escrita do negociador na conversa (tempo de chat da pessoa na margem). */
    void chatReply(TenantSeed seed, String key, HistoryCase c, String text, String idempotencyKey) {
        if (c.conversationId == null) {
            return;
        }
        rt.as(seed.actor(key), () -> messages.send(new SendMessageCommand(c.conversationId, null, null, null, null,
                null, null, null, text, null, idempotencyKey)));
    }

    // ------------------------------------------------------------------------------------------------ ligação

    /**
     * Ligação pelo clique para ligar do negociador, atendida no discador simulado: o relógio avança em passos curtos
     * durante a conversa (com o sinal de presença do operador a cada passo) e, no meio dela, {@code duringCall} roda
     * (respostas no chat de outro credor — atendimento simultâneo). Depois do encerramento, o pós-atendimento dura
     * {@code wrapUp} e termina com a tabulação, que registra o contato no caso.
     */
    void call(TenantSeed seed, String key, HistoryCase c, Duration talk, Duration wrapUp, String tabulation,
              String note, Consumer<Instant> duringCall, String idempotencyKey) {
        Actor operator = seed.actor(key);
        try {
            rt.as(operator, () -> agents.login(null));
        } catch (br.com.gestorcobranca.shared.domain.DomainException e) {
            if (!"agent.already_logged_in".equals(e.code())) {
                throw e;
            }
        }
        try {
            CallView created = rt.as(operator, () -> calls.clickToCall(new ClickToCallCommand(c.caseId, null),
                    idempotencyKey));
            if ("FAILED".equals(created.status())) {
                rt.warn("ligação do histórico negada: " + created.denialReasons());
                return;
            }
            UUID callId = created.id();
            rt.await("atendimento da ligação simulada", Duration.ofSeconds(30),
                    () -> answered(rt.as(operator, () -> calls.get(callId))));
            Instant end = rt.now().plus(talk);
            boolean chatted = false;
            while (rt.now().isBefore(end)) {
                Instant next = rt.now().plus(CALL_STEP);
                rt.advance(next.isAfter(end) ? end : next);
                rt.as(operator, () -> {
                    agents.heartbeat();
                    return null;
                });
                if (!chatted && duringCall != null && rt.now().isAfter(end.minus(talk.dividedBy(2)))) {
                    duringCall.accept(rt.now());
                    chatted = true;
                }
            }
            rt.asRun(seed.actor("integracao"), () -> telephonySandbox.simulateCallEvent(callId, "COMPLETED", null,
                    "DEBTOR"));
            rt.advance(rt.now().plus(wrapUp));
            rt.as(operator, () -> calls.tabulate(callId, new TabulateCommand(tabulation,
                    note + " (histórico fictício)", null, null, null, null)));
        } finally {
            rt.as(operator, agents::logout);
        }
    }

    private static boolean answered(CallView call) {
        return call.answeredAt() != null || CALL_LIVE.contains(call.status()) || "COMPLETED".equals(call.status())
                || "FAILED".equals(call.status());
    }

    private static EvidenceUpload receipt() {
        return new EvidenceUpload("comprovante-ficticio.pdf", "application/pdf", RECEIPT.length,
                new ByteArrayInputStream(RECEIPT));
    }

    static List<InstallmentView> open(List<InstallmentView> installments) {
        List<InstallmentView> list = new ArrayList<>();
        for (InstallmentView i : installments) {
            if ("OPEN".equals(i.status()) || "OVERDUE".equals(i.status())) {
                list.add(i);
            }
        }
        return list;
    }

    static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }
}
