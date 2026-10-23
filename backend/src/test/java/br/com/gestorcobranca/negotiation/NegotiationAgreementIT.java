package br.com.gestorcobranca.negotiation;

import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases;
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
import br.com.gestorcobranca.negotiation.application.service.NegotiationTestHooks;
import br.com.gestorcobranca.negotiation.domain.model.policy.StandardPolicyTerms;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.IntegrationEvent;
import br.com.gestorcobranca.shared.events.debt.DebtorAnonymized;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import br.com.gestorcobranca.shared.events.payment.PaymentReversed;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;

import java.io.InputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parte 2 do módulo negotiation contra PostgreSQL real: simular → propor → aprovar → aceitar
 * (termo) → formalizar → pagar parcelas → quitar (razão do título); quebra pela regra da política;
 * renegociação; estorno; handoff do assistente; escopo; RLS; concorrência na formalização;
 * expiração e anonimização.
 */
class NegotiationAgreementIT extends NegotiationTestSupport {

    @Autowired
    NegotiationSimulationUseCases simulations;
    @Autowired
    ProposalUseCases proposals;
    @Autowired
    AgreementUseCases agreements;
    @Autowired
    DebtTitleUseCases debtTitles;
    @Autowired
    ApplicationEventPublisher publisher;

    private static final JustificationInput WHY = new JustificationInput("PAYMENT_CAPACITY", "Renda reduzida");

    private UUID simulation(Integer level, JustificationInput why, PlanInput... plans) {
        return asOperator(() -> simulations.simulate(new SimulateCommand(creditorA, debtor, List.of(), null, level, why,
                List.of(plans), "PHONE")).id());
    }

    private ProposalView propose(UUID simulationId, int level, ComponentsView discounts, PlanInput plan, UUID replaced) {
        return asOperator(() -> proposals.create(new CreateProposalCommand(simulationId, level, discounts, plan, replaced,
                null, false, null)));
    }

    private static ComponentsView honorarium(String percent) {
        return new ComponentsView(null, null, null, null, null, new BigDecimal(percent));
    }

    private AgreementView acceptAndFormalize(UUID proposalId) {
        asOperator(() -> proposals.accept(proposalId, new AcceptCommand("PHONE", "call-123", null, null)));
        return asOperator(() -> proposals.formalize(proposalId));
    }

    private void publishAndAwait(IntegrationEvent event, String consumer) {
        tenantExecution.inTenant(tenant, "test.publish", () -> publisher.publishEvent(event));
        awaitTrue("consumo por " + consumer, () -> {
            Long consumed = jdbcTemplate.queryForObject(
                    "select count(*) from shared.consumed_event where consumer = ? and event_id = ?", Long.class,
                    consumer, event.eventId());
            return consumed != null && consumed == 1;
        });
    }

    private UUID pay(AgreementView a, int installment, BigDecimal amount) {
        UUID paymentId = Ids.newId();
        UUID installmentId = a.installments().get(installment - 1).id();
        publishAndAwait(new PaymentConfirmed(Ids.newId(), tenant, Instant.now(), paymentId, debtor, creditorA, portfolioA,
                a.id(), amount, Instant.now(), "PROVIDER", "PIX",
                List.of(new PaymentConfirmed.Allocation(null, installmentId, amount))), "negotiation.payment-confirmed");
        drainJobs("negotiation.");
        return paymentId;
    }

    private AgreementView agreement(UUID id) {
        return asManager(() -> agreements.get(id));
    }

    private String caseStatus() {
        return system(() -> jdbcTemplate.queryForObject("select status from collection.collection_case "
                + "where debtor_id = ? and portfolio_id = ? order by opened_at desc limit 1", String.class, debtor, portfolioA));
    }

    private <T> T asAssistantProposer(UUID policyId, Supplier<T> action) {
        return tenantExecution.callAs(Actor.assistant(tenant, policyId, "padrao", EnumSet.of(Permission.NEGOTIATION_SIMULATE,
                Permission.NEGOTIATION_PROPOSE, Permission.AGREEMENT_FORMALIZE), Set.of(creditorA)), action);
    }

    @Test
    void fullFlowApprovalAcceptanceTermFormalizationAndSettlementInTheTitleLedger() throws Exception {
        provisionTenant("flow");
        publishPolicy(creditorA, StandardPolicyTerms.marketDefault());
        assignProfile(manager, "GESTOR");
        UUID sim = simulation(2, WHY, new PlanInput(1, null, null));

        // júnior propõe nível 2 com 40% de honorários à vista: dentro da política, acima da alçada
        ProposalView pending = propose(sim, 2, honorarium("40"), new PlanInput(1, null, null), null);
        assertThat(pending.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(pending.authorityViolations()).contains("authority.level_exceeded", "authority.discount_exceeded");
        assertThat(pending.caseId()).isNotNull();
        // fora da política: 422, nada gravado
        assertThatThrownBy(() -> propose(sim, 2, honorarium("60"), new PlanInput(1, null, null), null))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("policy.discount_exceeded");
        // não aceita antes da aprovação; o operador não aprova
        assertThatThrownBy(() -> asOperator(() -> proposals.accept(pending.id(), new AcceptCommand("PHONE", null, null,
                null)))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> asOperator(() -> proposals.approve(pending.id(), null)))
                .isInstanceOf(ScopeAccessDeniedException.class);

        // fila do gestor (alçada suficiente)
        assertThat(asManager(() -> proposals.approvalQueue(PageQuery.of(0, 20))).content())
                .extracting(ProposalView::id).containsExactly(pending.id());
        ProposalView approved = asManager(() -> proposals.approve(pending.id(), "Cliente antigo"));
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(asManager(() -> proposals.approvalQueue(PageQuery.of(0, 20))).content()).isEmpty();

        // aceite: evidência + termo PDF com hash
        ProposalView accepted = asOperator(() -> proposals.accept(pending.id(), new AcceptCommand("PHONE", "call-1", null,
                null)));
        assertThat(accepted.acceptance().termSha256()).hasSize(64);
        ProposalUseCases.TermContent term = asOperator(() -> proposals.openTerm(pending.id()));
        try (InputStream in = term.content()) {
            byte[] bytes = in.readAllBytes();
            assertThat(new String(bytes, 0, 5)).isEqualTo("%PDF-");
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .isEqualTo(accepted.acceptance().termSha256());
        }
        assertThat(count("select count(*) from shared.access_log where event_type = 'FILE_DOWNLOADED'")).isEqualTo(1);

        // formalização pelo júnior: a aprovação cobre a alçada
        AgreementView agreement = asOperator(() -> proposals.formalize(pending.id()));
        assertThat(agreement.status()).isEqualTo("FORMALIZED");
        assertThat(agreement.origin()).isEqualTo("HUMAN");
        assertThat(agreement.participants()).extracting(AgreementUseCases.ParticipantView::role)
                .contains("CLOSER", "ORIGINATOR", "APPROVER");
        assertThat(agreement.installments()).singleElement()
                .satisfies(i -> assertThat(i.amount()).isEqualByComparingTo(agreement.totalAmount()));
        assertThat(agreement.acceptance().termSha256()).isEqualTo(accepted.acceptance().termSha256());
        assertThat(asOperator(() -> proposals.get(pending.id())).status()).isEqualTo("FORMALIZED");
        awaitTrue("caso em AGREEMENT", () -> "AGREEMENT".equals(caseStatus()));
        awaitTrue("atribuição consumida pelo incentive",
                () -> count("select count(*) from incentive.agreement_attribution where agreement_id = ?", agreement.id()) == 1);

        // pagamento integral → quitação; razão do título zerado (encargos reconhecidos + desconto)
        pay(agreement, 1, agreement.totalAmount());
        AgreementView settled = agreement(agreement.id());
        assertThat(settled.status()).isEqualTo("SETTLED");
        assertThat(settled.installments()).extracting(InstallmentView::status).containsExactly("PAID");
        assertThat(asManager(() -> agreements.payments(agreement.id()))).singleElement()
                .satisfies(p -> assertThat(p.ledgerStatus()).isEqualTo("POSTED"));
        assertThat(system(() -> debtTitles.get(title60)).status()).isEqualTo("SETTLED");
        assertThat(system(() -> debtTitles.get(title30)).status()).isEqualTo("SETTLED");
        assertThat(system(() -> debtTitles.get(title60)).openBalance()).isEqualByComparingTo("0");
        assertThat(count("select count(*) from debt.title_ledger_entry where entry_type = 'DISCOUNT'")).isPositive();

        // eventos de negócio sem dados pessoais
        for (String type : List.of("proposal.created", "proposal.approval_requested", "proposal.approved",
                "proposal.accepted", "agreement.formalized", "agreement.settled", "installment.paid")) {
            assertThat(count("select count(*) from shared.business_event where event_type = ?", type)).as(type).isPositive();
        }
        String attributes = system(() -> jdbcTemplate.queryForObject("select string_agg(attributes::text, ' ') "
                + "from shared.business_event where event_type like 'proposal.%' or event_type like 'agreement.%'", String.class));
        assertThat(attributes).doesNotContain("Maria", "529", "Renda", "Cliente antigo");
        assertThat(count("select count(*) from shared.audit_event where action in ('proposal.approved', "
                + "'agreement.formalized', 'agreement.payment_applied')")).isEqualTo(3);
    }

    @Test
    void installmentsReversalBreakAndRenegotiationWithoutRecountingRecovery() {
        provisionTenant("break");
        publishPolicy(creditorA, StandardPolicyTerms.marketDefault());
        assignProfile(manager, "GESTOR");
        UUID sim = simulation(null, null, new PlanInput(3, null, null));
        ProposalView draft = propose(sim, 0, null, new PlanInput(3, null, null), null);
        assertThat(draft.status()).isEqualTo("DRAFT");
        AgreementView agreement = acceptAndFormalize(draft.id());
        assertThat(agreement.installments()).hasSize(3);
        assertThat(agreement.breakRule().overdueInstallments()).isEqualTo(2);

        // 1ª parcela paga e estornada
        BigDecimal entry = agreement.installments().getFirst().amount();
        UUID payment = pay(agreement, 1, entry);
        assertThat(agreement(agreement.id()).status()).isEqualTo("IN_PAYMENT");
        assertThat(system(() -> debtTitles.get(title60)).paidTotal()).isPositive();
        publishAndAwait(new PaymentReversed(Ids.newId(), tenant, Instant.now(), Ids.newId(), payment, debtor, creditorA,
                portfolioA, agreement.id(), entry, "Chargeback"), "negotiation.payment-reversed");
        drainJobs("negotiation.");
        AgreementView reversed = agreement(agreement.id());
        assertThat(reversed.installments().getFirst().status()).isEqualTo("OPEN");
        assertThat(reversed.paidAmount()).isEqualByComparingTo("0");
        assertThat(asManager(() -> agreements.payments(agreement.id()))).singleElement()
                .satisfies(p -> assertThat(p.ledgerStatus()).isEqualTo("REVERSED"));
        assertThat(count("select count(*) from debt.title_ledger_entry where entry_type = 'REVERSAL' and reference_id = ?",
                payment)).isPositive();

        // paga de novo; a 2ª atrasa (atraso isolado não quebra); a 3ª também → quebra
        pay(agreement, 1, entry);
        system(() -> NegotiationTestHooks.sweepAgreements(applicationContext, today.plusDays(45)));
        AgreementView late = agreement(agreement.id());
        assertThat(late.status()).isEqualTo("IN_PAYMENT");
        assertThat(late.installments()).extracting(InstallmentView::status).containsExactly("PAID", "OVERDUE", "OPEN");
        assertThat(count("select count(*) from shared.business_event where event_type = 'installment.overdue'")).isEqualTo(1);
        system(() -> NegotiationTestHooks.sweepAgreements(applicationContext, today.plusDays(75)));
        AgreementView broken = agreement(agreement.id());
        assertThat(broken.status()).isEqualTo("BROKEN");
        assertThat(broken.installments()).extracting(InstallmentView::status).containsExactly("PAID", "CANCELLED", "CANCELLED");
        awaitTrue("caso volta a IN_NEGOTIATION", () -> "IN_NEGOTIATION".equals(caseStatus()));

        // renegociação: o júnior não tem renegociações na alçada → aprovação do gestor
        UUID resim = simulation(null, null, new PlanInput(1, null, null));
        ProposalView reneg = propose(resim, 0, null, new PlanInput(1, null, null), agreement.id());
        assertThat(reneg.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(reneg.authorityViolations()).containsExactly("authority.renegotiations_exceeded");
        assertThat(reneg.renegotiationNumber()).isEqualTo(1);
        asManager(() -> proposals.approve(reneg.id(), null));
        AgreementView renegotiated = acceptAndFormalize(reneg.id());
        assertThat(renegotiated.replacedAgreementId()).isEqualTo(agreement.id());
        assertThat(renegotiated.renegotiationNumber()).isEqualTo(1);
        AgreementView old = agreement(agreement.id());
        assertThat(old.status()).isEqualTo("RENEGOTIATED");
        assertThat(old.replacedByAgreementId()).isEqualTo(renegotiated.id());
        // ganho incremental = novo − (anterior − pago), nunca recontando o já recebido
        BigDecimal expected = renegotiated.totalAmount().subtract(old.totalAmount().subtract(old.paidAmount()))
                .max(BigDecimal.ZERO);
        assertThat(renegotiated.incrementalAmount()).isEqualByComparingTo(expected);
        assertThat(renegotiated.totalAmount()).isLessThan(old.totalAmount().add(BigDecimal.ONE));
        assertThat(count("select count(*) from shared.business_event where event_type = 'agreement.renegotiated'"))
                .isEqualTo(1);
        // um título nunca em dois acordos em vigor
        assertThat(count("select count(*) from negotiation.agreement_title where active")).isEqualTo(2);
    }

    @Test
    void assistantAboveAuthorityHandsOffAndHumanConfirmationIsAttributed() {
        provisionTenant("handoff");
        publishPolicy(creditorA, StandardPolicyTerms.marketDefault());
        assignProfile(manager, "GESTOR");
        UUID policy = Ids.newId();
        UUID sim = asAssistantProposer(policy, () -> simulations.simulate(new SimulateCommand(creditorA, debtor, List.of(),
                null, null, null, List.of(new PlanInput(1, null, null), new PlanInput(12, null, null)), "WHATSAPP")).id());

        // dentro da alçada do assistente: aceite registrado, mas o perfil inicial não formaliza
        ProposalView own = asAssistantProposer(policy, () -> proposals.create(new CreateProposalCommand(sim, 0, null,
                new PlanInput(1, null, null), null, null, false, null)));
        assertThat(own.status()).isEqualTo("DRAFT");
        asAssistantProposer(policy, () -> proposals.accept(own.id(), new AcceptCommand("WHATSAPP", "wamid-1", "200.10.0.1",
                "WhatsApp")));
        assertThatThrownBy(() -> asAssistantProposer(policy, () -> proposals.formalize(own.id())))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("não formaliza");

        // 12 parcelas: dentro da política, acima da alçada do assistente → transferência (não vai à fila)
        ProposalView handoff = asAssistantProposer(policy, () -> proposals.create(new CreateProposalCommand(sim, 0, null,
                new PlanInput(12, null, null), null, null, false, null)));
        assertThat(handoff.status()).isEqualTo("HANDED_OFF");
        assertThat(asManager(() -> proposals.approvalQueue(PageQuery.of(0, 20))).content()).isEmpty();
        assertThat(count("select count(*) from shared.business_event where event_type = 'proposal.handoff_requested'"))
                .isEqualTo(1);
        // transferência pelo contrato do communication (consumido como communication.handoff-requested)
        awaitTrue("handoff consumido pelo communication", () -> {
            Long consumed = jdbcTemplate.queryForObject("select count(*) from shared.consumed_event "
                    + "where consumer = 'communication.handoff-requested' and tenant_id = ?", Long.class, tenant);
            return consumed != null && consumed >= 1;
        });
        // o assistente não vê propostas de humanos; o humano assume a sugestão
        ProposalView adopted = asManager(() -> proposals.adopt(handoff.id(), "PHONE"));
        assertThat(adopted.status()).isEqualTo("DRAFT");
        assertThat(adopted.handoffProposalId()).isEqualTo(handoff.id());
        assertThatThrownBy(() -> asAssistantProposer(policy, () -> proposals.get(adopted.id())))
                .isInstanceOf(ResourceNotFoundException.class);
        asManager(() -> proposals.accept(adopted.id(), new AcceptCommand("PHONE", null, null, null)));
        AgreementView agreement = asManager(() -> proposals.formalize(adopted.id()));
        assertThat(agreement.origin()).isEqualTo("ASSISTANT_HANDOFF_CONFIRMED");
        assertThat(agreement.participants()).anySatisfy(p -> {
            assertThat(p.role()).isEqualTo("PRE_NEGOTIATOR");
            assertThat(p.actorId()).isEqualTo(policy);
        });
        awaitTrue("atribuição consumida", () -> count("select count(*) from incentive.agreement_attribution "
                + "where agreement_id = ? and origin = 'ASSISTANT_HANDOFF_CONFIRMED'", agreement.id()) == 1);
        // a proposta do assistente aceita ficou sem acordo: os títulos já estão cobertos
        assertThatThrownBy(() -> asManager(() -> proposals.formalize(own.id()))).isInstanceOf(DomainException.class)
                .hasMessageContaining("title.under_agreement");
    }

    @Test
    void concurrentFormalizationCreatesASingleAgreement() throws Exception {
        provisionTenant("concurrency");
        publishPolicy(creditorA, StandardPolicyTerms.marketDefault());
        UUID sim = simulation(null, null, new PlanInput(1, null, null));
        ProposalView p1 = propose(sim, 0, null, new PlanInput(1, null, null), null);
        ProposalView p2 = propose(sim, 0, null, new PlanInput(1, null, null), null);
        for (ProposalView p : List.of(p1, p2)) {
            asOperator(() -> proposals.accept(p.id(), new AcceptCommand("PHONE", null, null, null)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (UUID id : List.of(p1.id(), p1.id(), p2.id())) {
            tasks.add(() -> {
                start.await();
                try {
                    asOperator(() -> proposals.formalize(id));
                    return "OK";
                } catch (RuntimeException e) {
                    return "FAIL";
                }
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        tasks.forEach(t -> futures.add(pool.submit(t)));
        start.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();
        assertThat(results).containsOnlyOnce("OK");
        assertThat(count("select count(*) from negotiation.agreement")).isEqualTo(1);
        assertThat(count("select count(*) from negotiation.agreement_title where active")).isEqualTo(2);
    }

    @Test
    void scopeRlsExpirationAndAnonymization() {
        provisionTenant("scope");
        publishPolicy(creditorA, StandardPolicyTerms.marketDefault());
        UUID sim = simulation(1, WHY, new PlanInput(1, null, today.plusDays(7)));
        // validade: 5 dias (política), limitada ao 1º vencimento
        ProposalView p = propose(sim, 1, null, new PlanInput(1, null, today.plusDays(7)), null);
        assertThat(p.validUntil()).isEqualTo(today.plusDays(5));
        assertThat(p.status()).isEqualTo("DRAFT");
        assertThat(p.justificationReason()).isEqualTo("PAYMENT_CAPACITY");

        // escopo: operador restrito ao credor B não vê nem lista a proposta do credor A
        assertThatThrownBy(() -> as(operator, DefaultRoles.OPERADOR, CreditorScope.of(Set.of(creditorB)),
                () -> proposals.get(p.id()))).isInstanceOf(ResourceNotFoundException.class);
        assertThat(as(operator, DefaultRoles.OPERADOR, CreditorScope.of(Set.of(creditorB)),
                () -> proposals.search(new ProposalFilter(null, null, null, null, null), PageQuery.of(0, 20))).content()).isEmpty();
        assertThatThrownBy(() -> as(operator, DefaultRoles.OPERADOR, CreditorScope.of(Set.of(creditorB)),
                () -> proposals.create(new CreateProposalCommand(sim, 0, null, new PlanInput(1, null, null), null, null,
                        false, null)))).isInstanceOf(ResourceNotFoundException.class);

        // expiração pela validade (5 dias na política padrão)
        assertThat(system(() -> NegotiationTestHooks.expireProposals(applicationContext, today.plusDays(3)))).isZero();
        assertThat(system(() -> NegotiationTestHooks.expireProposals(applicationContext, today.plusDays(10)))).isEqualTo(1);
        assertThat(asOperator(() -> proposals.get(p.id())).status()).isEqualTo("EXPIRED");
        assertThat(count("select count(*) from shared.business_event where event_type = 'proposal.expired'")).isEqualTo(1);

        // LGPD: a anonimização do devedor apaga a observação livre (simulação e proposta) e mantém o motivo
        assertThat(count("select count(*) from negotiation.simulation where justification_note is not null")).isEqualTo(1);
        publishAndAwait(new DebtorAnonymized(Ids.newId(), tenant, Instant.now(), debtor), "negotiation.debtor-anonymized");
        assertThat(count("select count(*) from negotiation.simulation where justification_note is not null")).isZero();
        assertThat(count("select count(*) from negotiation.simulation where calculation -> 'justification' ->> 'note' "
                + "is not null")).isZero();
        assertThat(count("select count(*) from negotiation.simulation where justification_reason = 'PAYMENT_CAPACITY'"))
                .isEqualTo(1);
        assertThat(count("select count(*) from negotiation.proposal where justification_note is not null")).isZero();
        // a simulação continua imutável fora da anonimização
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update negotiation.simulation set granted_level = 0 where id = ?", sim)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update negotiation.simulation set justification_note = 'x' where id = ?", sim)))
                .isInstanceOf(DataAccessException.class);

        // RLS: outra assessoria não vê propostas nem acordos
        UUID tenantA = tenant;
        UUID proposalA = p.id();
        provisionTenant("scope-b");
        assertThat(count("select count(*) from negotiation.proposal")).isZero();
        assertThatThrownBy(() -> asManager(() -> proposals.get(proposalA))).isInstanceOf(ResourceNotFoundException.class);
        assertThat(jdbcTemplate.queryForObject("select count(*) from negotiation.proposal", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from negotiation.agreement", Long.class)).isZero();
        assertThat(tenantA).isNotEqualTo(tenant);
    }
}
