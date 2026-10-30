package br.com.gestorcobranca.sandbox.application.service;

import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsRefreshUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.OpenDisputeCommand;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.CallbackInput;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.PromiseInput;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.RecordInteractionCommand;
import br.com.gestorcobranca.collection.application.port.in.QueueUseCases;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.ConversationView;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.InboxFilter;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases;
import br.com.gestorcobranca.communication.application.port.in.SandboxUseCases;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases.CreateRestrictionCommand;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases.RestrictionFilter;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestUseCases;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestUseCases.OpenRequestCommand;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestUseCases.RequestFilter;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementFilter;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.InstallmentView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.ComponentsView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.JustificationInput;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.PlanInput;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.SimulateCommand;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.AcceptCommand;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.CreateProposalCommand;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.ProposalFilter;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.ProposalView;
import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases.CreateInstructionCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases.InstructionView;
import br.com.gestorcobranca.payment.application.port.in.PaymentSandboxUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.EvidenceUpload;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ExternalPaymentCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ReverseCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.TargetInput;
import br.com.gestorcobranca.sandbox.domain.model.DemoPortfolioData.Debt;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.CreateStatementCommand;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.StatementFilter;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Cenários operacionais para todas as telas principais terem conteúdo: interações com tabulação, promessas, retornos
 * agendados, acordos em estados diferentes (formalizado, em pagamento, quitado, cancelado, proposta aguardando
 * aprovação), pagamentos (Pix confirmado pelo PSP simulado, externo pendente de revisão, estornado), contestações,
 * restrição de contato, mensagem recebida, pedido de titular (LGPD), distribuição de casos e demonstrativo do credor.
 * Cada ação é feita pelo perfil que a faria na operação (operador, supervisor, financeiro, compliance…) e cada
 * cenário confere se já foi feito antes de agir (idempotência por cenário).
 */
@SandboxSeedComponent
class OperationSeeder {

    /** Comprovante fictício (PDF mínimo válido: a evidência é conferida pelo conteúdo). */
    private static final byte[] RECEIPT = ("%PDF-1.4\n% comprovante ficticio do sandbox\n1 0 obj<<>>endobj\n"
            + "trailer<<>>\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);
    private static final JustificationInput CAPACITY = new JustificationInput("PAYMENT_CAPACITY",
            "Renda reduzida (cenário fictício)");

    private final DebtorUseCases debtors;
    private final CaseUseCases cases;
    private final CaseWorkUseCases caseWork;
    private final QueueUseCases queues;
    private final InteractionUseCases interactions;
    private final DisputeUseCases disputes;
    private final NegotiationSimulationUseCases simulations;
    private final ProposalUseCases proposals;
    private final AgreementUseCases agreements;
    private final PaymentInstructionUseCases instructions;
    private final PaymentSandboxUseCases pixSandbox;
    private final PaymentUseCases payments;
    private final ContactRestrictionUseCases restrictions;
    private final DataSubjectRequestUseCases dataSubjectRequests;
    private final SandboxUseCases messagingSandbox;
    private final MessageUseCases messages;
    private final ConversationUseCases conversations;
    private final SettlementStatementUseCases statements;
    private final AnalyticsRefreshUseCases analytics;
    private final SeedRuntime rt;

    OperationSeeder(DebtorUseCases debtors, CaseUseCases cases, CaseWorkUseCases caseWork, QueueUseCases queues,
                    InteractionUseCases interactions, DisputeUseCases disputes,
                    NegotiationSimulationUseCases simulations, ProposalUseCases proposals, AgreementUseCases agreements,
                    PaymentInstructionUseCases instructions, PaymentSandboxUseCases pixSandbox, PaymentUseCases payments,
                    ContactRestrictionUseCases restrictions, DataSubjectRequestUseCases dataSubjectRequests,
                    SandboxUseCases messagingSandbox, MessageUseCases messages, ConversationUseCases conversations,
                    SettlementStatementUseCases statements,
                    AnalyticsRefreshUseCases analytics, SeedRuntime rt) {
        this.debtors = debtors;
        this.cases = cases;
        this.caseWork = caseWork;
        this.queues = queues;
        this.interactions = interactions;
        this.disputes = disputes;
        this.simulations = simulations;
        this.proposals = proposals;
        this.agreements = agreements;
        this.instructions = instructions;
        this.pixSandbox = pixSandbox;
        this.payments = payments;
        this.restrictions = restrictions;
        this.dataSubjectRequests = dataSubjectRequests;
        this.messagingSandbox = messagingSandbox;
        this.messages = messages;
        this.conversations = conversations;
        this.statements = statements;
        this.analytics = analytics;
        this.rt = rt;
    }

    /** Devedor de um cenário: caso ativo na carteira (nulo se o caso já foi encerrado numa carga anterior). */
    private record Target(String ref, UUID debtorId, UUID creditorId, UUID portfolioId, CaseView collectionCase,
                          Debt debt) {
    }

    void seed(TenantSeed seed) {
        // 1º credor, carteira principal: um cenário por devedor de perfil controlado
        interactionsThenAgreementPaidByPix(seed, target(seed, 0, 0, 0), 3);
        agreementSettledByPix(seed, target(seed, 0, 0, 1));
        externalPaymentPendingReview(seed, target(seed, 0, 0, 2));
        proposalAwaitingApproval(seed, target(seed, 0, 0, 3));
        promise(seed, target(seed, 0, 0, 4), "operador");
        callback(seed, target(seed, 0, 0, 5));
        dispute(seed, target(seed, 0, 0, 6), "ALREADY_PAID", "Devedor afirma ter pago no caixa da loja", false);
        dispute(seed, target(seed, 0, 0, 7), "WRONG_AMOUNT", "Devedor contesta juros cobrados na parcela", true);
        reversedPayment(seed, target(seed, 0, 0, 8));
        cancelledAgreement(seed, target(seed, 0, 0, 9));
        optOut(seed, target(seed, 0, 0, 10));
        inboundMessage(seed, target(seed, 0, 0, 11), "Oi, recebi uma ligação de vocês. Quero entender o valor e negociar.");
        // 2º e 3º credores: acordos, promessas e mensagens também em outros credores
        interactionsThenAgreementPaidByPix(seed, target(seed, 1, 0, 0), 6);
        promise(seed, target(seed, 1, 0, 1), "operador");
        inboundMessage(seed, target(seed, 1, 0, 2), "Boa tarde. Consigo parcelar essa dívida?");
        formalizedAgreement(seed, target(seed, 2, 0, 0), 2);
        unsuccessfulContacts(seed, target(seed, 2, 0, 1));
        dataSubjectRequest(seed, seed.debt(1, 0, 5));
    }

    /** Depois da régua: distribui casos da fila do 1º credor, demonstrativo do credor e atualização dos painéis. */
    void finish(TenantSeed seed) {
        Actor supervisor = seed.actor("supervisor");
        UUID queueId = seed.queues.get(seed.creditor(0).prefix());
        rt.optional("distribuição da fila do 1º credor", () -> {
            if (rt.as(supervisor, () -> queues.summary(queueId)).assigned() == 0) {
                rt.as(supervisor, () -> caseWork.distribute(queueId, 12));
            }
        });
        Actor financeiro = seed.actor("financeiro");
        UUID creditorId = seed.creditorId(0);
        rt.optional("demonstrativo de prestação de contas", () -> {
            if (rt.as(financeiro, () -> statements.search(new StatementFilter(creditorId, null), PageQuery.of(0, 1)))
                    .totalElements() == 0) {
                UUID id = rt.as(financeiro, () -> statements.create(new CreateStatementCommand(creditorId, null, null))).id();
                rt.as(financeiro, () -> statements.calculate(id));
            }
        });
        // os painéis também são atualizados pelo agendamento (a cada 15 min); atualização recente dispensa a manual
        rt.optional("atualização dos painéis", Set.of("analytics.refresh_too_soon"),
                () -> rt.as(seed.actor("gestor"), analytics::refreshNow));
    }

    // -------------------------------------------------------------------------------------------- cenários

    private void interactionsThenAgreementPaidByPix(TenantSeed seed, Target t, int installments) {
        if (t == null) {
            return;
        }
        rt.optional("interações e acordo pago por Pix (" + t.ref() + ")", () -> {
            interaction(seed, t, "operador", "NO_ANSWER", "Chamou até cair na caixa postal", Duration.ofDays(2), null, null);
            interaction(seed, t, "operador", "CPC_NEGOTIATING", "Devedor atendeu e pediu proposta parcelada",
                    Duration.ofHours(3), null, null);
            AgreementView agreement = agreement(seed, t, "operador", installments);
            payByPix(seed, agreement, 0);
        });
    }

    private void agreementSettledByPix(TenantSeed seed, Target t) {
        if (t == null) {
            return;
        }
        rt.optional("acordo à vista quitado por Pix (" + t.ref() + ")", () -> {
            AgreementView agreement = agreement(seed, t, "operador", 1);
            payByPix(seed, agreement, 0);
        });
    }

    private void formalizedAgreement(TenantSeed seed, Target t, int installments) {
        if (t == null) {
            return;
        }
        rt.optional("acordo formalizado (" + t.ref() + ")", () -> agreement(seed, t, "operador", installments));
    }

    private void externalPaymentPendingReview(TenantSeed seed, Target t) {
        if (t == null) {
            return;
        }
        rt.optional("pagamento externo pendente de revisão (" + t.ref() + ")", () -> {
            if (hasPayments(seed, t)) {
                return;
            }
            AgreementView agreement = agreement(seed, t, "operador", 2);
            // quitação do acordo por transferência: acima do limite de revisão (padrão R$ 500,00), aguarda segunda pessoa
            // com PAYMENT_REVIEW e suspende a quebra do acordo enquanto isso
            rt.as(seed.actor("operador"), () -> payments.registerExternal(new ExternalPaymentCommand(t.creditorId(),
                    t.debtorId(), agreement.remainingAmount(), rt.now().minus(Duration.ofHours(5)), "TRANSFER", "AGENCY_ACCOUNT",
                    "SBX-TED-" + t.ref(), "Comprovante de transferência enviado pelo devedor (fictício)",
                    new TargetInput("AGREEMENT", agreement.id(), List.of(), null)), receipt(),
                    "sandbox-" + seed.spec.slug() + "-review-" + t.ref()));
        });
    }

    private void reversedPayment(TenantSeed seed, Target t) {
        if (t == null) {
            return;
        }
        rt.optional("pagamento estornado (" + t.ref() + ")", () -> {
            if (hasPayments(seed, t)) {
                return;
            }
            PaymentView payment = rt.as(seed.actor("operador"), () -> payments.registerExternal(new ExternalPaymentCommand(
                    t.creditorId(), t.debtorId(), new BigDecimal("300.00"), rt.now().minus(Duration.ofDays(3)), "PIX",
                    "AGENCY_ACCOUNT", "SBX-PIX-" + t.ref(), "Pagamento parcial identificado no extrato (fictício)",
                    new TargetInput("TITLES", null, List.of(firstTitle(seed, t)), "OLDEST_FIRST")), receipt(),
                    "sandbox-" + seed.spec.slug() + "-reversal-" + t.ref()));
            if (Set.of("CONFIRMED", "RECONCILED").contains(payment.status())) {
                rt.as(seed.actor("financeiro"), () -> payments.reverse(payment.id(), new ReverseCommand("BANK_RETURN",
                        "Transferência devolvida pelo banco do pagador (cenário fictício)")));
            }
        });
    }

    private void proposalAwaitingApproval(TenantSeed seed, Target t) {
        if (t == null) {
            return;
        }
        rt.optional("proposta acima da alçada aguardando aprovação (" + t.ref() + ")", () -> {
            Actor operator = seed.actor("operador2");
            boolean exists = rt.as(operator, () -> proposals.search(new ProposalFilter(t.creditorId(), t.debtorId(), null,
                    null, null), PageQuery.of(0, 1))).totalElements() > 0;
            if (exists) {
                return;
            }
            interaction(seed, t, "operador2", "CPC_NEGOTIATING", "Devedor só fecha à vista com desconto nos honorários",
                    Duration.ofHours(1), null, null);
            // nível 2 com 40% dos honorários à vista: dentro da política, acima da alçada do negociador júnior
            UUID simulation = rt.as(operator, () -> simulations.simulate(new SimulateCommand(t.creditorId(), t.debtorId(),
                    List.of(), null, 2, CAPACITY, List.of(new PlanInput(1, null, null)), "PHONE"))).id();
            rt.as(operator, () -> proposals.create(new CreateProposalCommand(simulation, 2,
                    new ComponentsView(null, null, null, null, null, new BigDecimal("40")), new PlanInput(1, null, null),
                    null, null, false, "PHONE")));
        });
    }

    private void cancelledAgreement(TenantSeed seed, Target t) {
        if (t == null) {
            return;
        }
        rt.optional("acordo cancelado (" + t.ref() + ")", () -> {
            AgreementView agreement = agreement(seed, t, "operador", 4);
            if (Set.of("FORMALIZED", "IN_PAYMENT").contains(agreement.status())) {
                rt.as(seed.actor("supervisor"), () -> agreements.cancel(agreement.id(),
                        "Devedor desistiu antes do primeiro pagamento (cenário fictício)"));
            }
        });
    }

    private void promise(TenantSeed seed, Target t, String actorKey) {
        if (t == null) {
            return;
        }
        BigDecimal amount = t.collectionCase().openBalance().multiply(new BigDecimal("0.40"))
                .setScale(0, RoundingMode.DOWN).setScale(2, RoundingMode.UNNECESSARY);
        rt.optional("promessa de pagamento (" + t.ref() + ")", () -> interaction(seed, t, actorKey, "PROMISE",
                "Prometeu pagar parte do saldo com o salário", Duration.ofHours(4),
                new PromiseInput(amount, seed.today.plusDays(5)), null));
    }

    private void callback(TenantSeed seed, Target t) {
        if (t == null) {
            return;
        }
        rt.optional("retorno agendado (" + t.ref() + ")", () -> interaction(seed, t, "operador", "CALLBACK",
                "Pediu para retornar amanhã depois do almoço", Duration.ofHours(2), null,
                new CallbackInput(LocalDateTime.of(seed.today.plusDays(1), LocalTime.of(14, 0)), seed.user("operador"),
                        "Retornar com proposta em até 6 parcelas")));
    }

    private void unsuccessfulContacts(TenantSeed seed, Target t) {
        if (t == null) {
            return;
        }
        rt.optional("tentativas sem sucesso (" + t.ref() + ")", () -> {
            interaction(seed, t, "operador", "NO_ANSWER", "Não atendeu", Duration.ofDays(3), null, null);
            interaction(seed, t, "operador", "THIRD_PARTY", "Recado deixado com familiar", Duration.ofDays(1), null, null);
        });
    }

    private void dispute(TenantSeed seed, Target t, String type, String description, boolean startReview) {
        if (t == null) {
            return;
        }
        rt.optional("contestação " + type + " (" + t.ref() + ")", () -> {
            UUID caseId = t.collectionCase().id();
            List<DisputeView> existing = rt.as(seed.actor("supervisor"), () -> disputes.listByCase(caseId));
            DisputeView dispute = existing.isEmpty()
                    ? rt.as(seed.actor("operador"), () -> disputes.open(caseId, new OpenDisputeCommand(
                    Set.of(firstTitle(seed, t)), type, "DEBTOR", description + " (cenário fictício)", null, null)))
                    : existing.getFirst();
            if (startReview && "OPEN".equals(dispute.status())) {
                rt.as(seed.actor("supervisor"), () -> disputes.startReview(dispute.id()));
            }
        });
    }

    private void optOut(TenantSeed seed, Target t) {
        if (t == null) {
            return;
        }
        Actor compliance = seed.actor("compliance");
        rt.optional("restrição de contato (" + t.ref() + ")", () -> {
            if (rt.as(compliance, () -> restrictions.search(new RestrictionFilter(t.debtorId(), null, null, null, null),
                    PageQuery.of(0, 1))).totalElements() == 0) {
                rt.as(compliance, () -> restrictions.create(new CreateRestrictionCommand(t.debtorId(), null, "WHATSAPP",
                        "OPT_OUT", null, null, "Pediu para não receber mensagens por WhatsApp (cenário fictício)", "DEBTOR",
                        null, null)));
            }
        });
    }

    private void inboundMessage(TenantSeed seed, Target t, String text) {
        if (t == null) {
            return;
        }
        rt.optional("mensagem recebida no WhatsApp (" + t.ref() + ")", () -> {
            long existing = rt.as(seed.actor("supervisor"), () -> messages.listByDebtor(t.debtorId(), null,
                    PageQuery.of(0, 1))).totalElements();
            if (existing > 0) {
                return;
            }
            rt.asRun(seed.actor("integracao"), () -> messagingSandbox.simulateInbound(seed.whatsappAccountId,
                    t.debt().debtor().phone(), text));
            // contato desconhecido chega sem devedor (por segurança): o supervisor identifica o devedor na caixa de entrada
            Actor supervisor = seed.actor("supervisor");
            rt.as(supervisor, () -> conversations.inbox(new InboxFilter(null, null, null, null, "WHATSAPP", null, null,
                    null, null, null, null), PageQuery.of(0, 50))).content().stream()
                    .filter(c -> c.debtorId() == null && c.lastInboundAt() != null)
                    .max(java.util.Comparator.comparing(ConversationView::lastInboundAt))
                    .ifPresent(c -> rt.as(supervisor, () -> conversations.identify(c.id(), t.debtorId(), t.creditorId())));
        });
    }

    private void dataSubjectRequest(TenantSeed seed, Debt debt) {
        Actor compliance = seed.actor("compliance");
        rt.optional("solicitação de titular (LGPD)", () -> {
            if (rt.as(compliance, () -> dataSubjectRequests.search(new RequestFilter(null, null, null,
                    debt.debtor().document(), null), PageQuery.of(0, 1))).totalElements() == 0) {
                rt.as(compliance, () -> dataSubjectRequests.open(new OpenRequestCommand("ACCESS", "EMAIL",
                        debt.debtor().name(), debt.debtor().document(),
                        debt.debtor().email() != null ? debt.debtor().email() : "titular@exemplo.test",
                        "Solicita cópia dos dados pessoais tratados (cenário fictício)", rt.now().minus(Duration.ofDays(2)))));
            }
        });
    }

    // -------------------------------------------------------------------------------------------- apoio

    private Target target(TenantSeed seed, int creditor, int portfolio, int index) {
        Debt debt = seed.debt(creditor, portfolio, index);
        UUID portfolioId = seed.portfolioId(creditor, portfolio);
        String ref = seed.creditor(creditor).prefix() + "-" + (index + 1);
        return rt.system(seed.tenantId, () -> debtors.findByDocument(debt.debtor().document())
                .flatMap(d -> cases.findActive(d.id(), portfolioId).map(c -> new Target(ref, d.id(), seed.creditorId(creditor),
                        portfolioId, c, debt)))).orElse(null);
    }

    /** Já há pagamento do devedor no credor (cenário de pagamento feito numa carga anterior). */
    private boolean hasPayments(TenantSeed seed, Target t) {
        return rt.as(seed.actor("financeiro"), () -> payments.search(new PaymentUseCases.PaymentFilter(t.creditorId(),
                t.debtorId(), null, null, null, null, null, null), PageQuery.of(0, 1))).totalElements() > 0;
    }

    private UUID firstTitle(TenantSeed seed, Target t) {
        return rt.system(seed.tenantId, () -> cases.sheet(t.collectionCase().id())).titles().getFirst().titleId();
    }

    /** Interação registrada pelo negociador (idempotente pela referência externa). */
    private void interaction(TenantSeed seed, Target t, String actorKey, String tabulation, String note, Duration ago,
                             PromiseInput promise, CallbackInput callback) {
        var started = rt.now().minus(ago);
        rt.as(seed.actor(actorKey), () -> interactions.record(t.collectionCase().id(), new RecordInteractionCommand(
                "PHONE", "OUTBOUND", null, tabulation, note + " (cenário fictício)", null, started, started.plusSeconds(95),
                promise, callback, null, "MANUAL", "sandbox-" + seed.spec.slug() + "-" + t.ref() + "-" + tabulation)));
    }

    /** Acordo do devedor no credor (reaproveita o existente): simulação → proposta na alçada → aceite → formalização. */
    private AgreementView agreement(TenantSeed seed, Target t, String actorKey, int installments) {
        Actor operator = seed.actor(actorKey);
        Optional<AgreementView> existing = rt.as(operator, () -> agreements.search(new AgreementFilter(t.creditorId(),
                t.debtorId(), null, null), PageQuery.of(0, 1))).content().stream().findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID simulation = rt.as(operator, () -> simulations.simulate(new SimulateCommand(t.creditorId(), t.debtorId(),
                List.of(), null, null, null, List.of(new PlanInput(installments, null, null)), "PHONE"))).id();
        ProposalView proposal = rt.as(operator, () -> proposals.create(new CreateProposalCommand(simulation, 0, null,
                new PlanInput(installments, null, null), null, null, false, "PHONE")));
        rt.as(operator, () -> proposals.accept(proposal.id(), new AcceptCommand("PHONE",
                "sandbox-aceite-" + seed.spec.slug() + "-" + t.ref(), null, null)));
        return rt.as(operator, () -> proposals.formalize(proposal.id()));
    }

    /** Pix da parcela pelo PSP simulado: instrução registrada (job), pagamento e notificação assinada ao webhook. */
    private void payByPix(TenantSeed seed, AgreementView agreement, int installmentIndex) {
        InstallmentView installment = agreement.installments().get(installmentIndex);
        if (!"OPEN".equals(installment.status()) && !"OVERDUE".equals(installment.status())) {
            return;
        }
        Actor operator = seed.actor("operador");
        InstructionView created = rt.as(operator, () -> instructions.create(new CreateInstructionCommand("PIX_IMMEDIATE",
                agreement.id(), installment.id(), null, null, null, null, "WHATSAPP"),
                "sandbox-pix-" + installment.id()));
        rt.await("registro do Pix no PSP simulado", () -> !"PENDING".equals(
                rt.as(operator, () -> instructions.get(created.id())).status()));
        if ("ACTIVE".equals(rt.as(operator, () -> instructions.get(created.id())).status())) {
            rt.as(seed.actor("integracao"), () -> pixSandbox.simulatePixPayment(created.id(), null, true));
        }
        rt.await("confirmação do Pix", () -> "PAID".equals(rt.as(operator, () -> instructions.get(created.id())).status()));
    }

    private static EvidenceUpload receipt() {
        return new EvidenceUpload("comprovante-ficticio.pdf", "application/pdf", RECEIPT.length,
                new ByteArrayInputStream(RECEIPT));
    }
}
