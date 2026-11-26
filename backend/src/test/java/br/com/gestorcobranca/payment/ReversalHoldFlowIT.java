package br.com.gestorcobranca.payment;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.ContactDecision;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.ContactRequest;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.DenialReason;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.debt.application.port.in.ReversalHoldUseCases;
import br.com.gestorcobranca.debt.application.port.in.ReversalHoldUseCases.HoldFilter;
import br.com.gestorcobranca.debt.application.port.in.ReversalHoldUseCases.ReleaseCommand;
import br.com.gestorcobranca.debt.application.port.out.DebtPendingWorkQueries;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.negotiation.application.port.out.CaseEligibilityPort;
import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases.CreateInstructionCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentReversalHoldsUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentReversalHoldsUseCases.HeldReversalItem;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ExternalPaymentCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ReverseCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.TargetInput;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ReallocateCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentSettingsUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.CreateProposalCommand;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.AcceptCommand;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.SimulateCommand;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.PlanInput;
import java.util.EnumSet;
import java.time.LocalDate;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.junit.jupiter.api.extension.ExtendWith;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.scoring.application.port.in.ScoringSettingsUseCases.SettingsCommand;
import br.com.gestorcobranca.scoring.application.port.in.ScoringSettingsUseCases.LegalBasisCommand;
import br.com.gestorcobranca.scoring.application.port.in.ScoringSettingsUseCases;
import br.com.gestorcobranca.publicapi.domain.model.TokenScope;
import br.com.gestorcobranca.negotiation.application.service.NegotiationTestHooks;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.TitleRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ObligationPartyRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ImportSummary;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContractRecord;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Retenção da cobrança após estorno de pagamento recebido pelo credor (H07 parte 3) contra PostgreSQL real: criada na
 * transação do estorno (RS-108 a, c), independente do status do título (RS-109), respeitada pelo porteiro em todos os
 * canais (RS-110), pela elegibilidade (RS-111), pela negociação e pela instrução de pagamento (RS-112), liberada só por
 * outra pessoa com justificativa e confirmação (RS-115, RS-116), informes seguintes do credor em revisão (RS-120) e
 * encerrada pela anonimização do titular (RS-122).
 */
@ExtendWith(OutputCaptureExtension.class)
class ReversalHoldFlowIT extends PaymentTestSupport {

    @Autowired
    ReversalHoldUseCases holds;
    @Autowired
    PaymentReversalHoldsUseCases reversalHolds;
    @Autowired
    ContactGate contactGate;
    @Autowired
    CaseEligibilityPort negotiationEligibility;
    @Autowired
    CaseUseCases cases;
    @Autowired
    DebtPendingWorkQueries pendingWork;
    @Autowired
    DebtorUseCases debtorUseCases;
    @Autowired
    ScoringSettingsUseCases scoringSettings;
    @Autowired
    PaymentSettingsUseCases paymentSettings;

    private final Instant paidAt = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

    private ExternalPaymentCommand toTitle(UUID creditorId, UUID debtorId, UUID titleId, String amount, String origin) {
        return new ExternalPaymentCommand(creditorId, debtorId, new BigDecimal(amount), paidAt, "PIX", origin, null,
                null, new TargetInput("TITLES", null, List.of(titleId), null));
    }

    private HoldFilter pendingToday() {
        return new HoldFilter(today.minusDays(1), today.plusDays(1), null, null);
    }

    private List<DenialReason> collectionDenials(UUID debtorId, UUID creditorId, String channel, String origin) {
        ContactDecision d = system(() -> contactGate.evaluate(new ContactRequest(debtorId, creditorId, channel, null,
                null, origin, null, null, null)));
        return d.reasons();
    }

    @Test
    void creditorReceivedReversalHoldsTheCollectionUntilAnotherPersonReleases() {
        provision("retencao");
        PaymentView paid = asOperator(() -> payments.registerExternal(toTitle(creditorB, debtor, titleB, "100.00",
                "CREDITOR_DIRECT"), pdf(), null));
        assertThat(paid.status()).isEqualTo("CONFIRMED");
        // pago na conta da assessoria: o estorno retoma a cobrança normalmente (sem retenção)
        PaymentView agency = asOperator(() -> payments.registerExternal(toTitle(creditorA, debtor, title30, "50.00",
                "AGENCY_ACCOUNT"), pdf(), null));
        asFinance1(() -> payments.reverse(agency.id(), new ReverseCommand("BANK_RETURN", "Devolvido pelo banco")));
        assertThat(count("select count(*) from debt.reversal_hold where payment_id = ?", agency.id())).isZero();

        // RS-108 (a): estorno manual → retenção PENDING na mesma transação, com o estorno e o título
        asFinance1(() -> payments.reverse(paid.id(), new ReverseCommand("OTHER", "Credor desfez o informe")));
        UUID reversalId = system(() -> jdbcTemplate.queryForObject(
                "select id from payment.payment_reversal where payment_id = ?", UUID.class, paid.id()));
        assertThat(count("select count(*) from debt.reversal_hold where payment_id = ? and reversal_id = ? "
                + "and state = 'PENDING' and placed_by = ? and informed_by = ?", paid.id(), reversalId, finance1,
                operator)).isEqualTo(1);
        assertThat(system(() -> holds.heldTitleIds(creditorB, debtor))).containsExactly(titleB);
        assertThat(system(() -> debtTitles.get(titleB)).status()).isEqualTo("OPEN");
        // RS-108 (c): estorno repetido é recusado e não cria segunda retenção
        assertThatThrownBy(() -> asFinance1(() -> payments.reverse(paid.id(), new ReverseCommand("OTHER", "Credor desfez o informe"))))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "payment.not_reversible");
        assertThat(count("select count(*) from debt.reversal_hold where payment_id = ?", paid.id())).isEqualTo(1);

        // RS-110: porteiro nega a cobrança do credor B em todos os canais e origens; o credor A não é afetado
        for (String channel : List.of("WHATSAPP", "EMAIL", "SMS", "PHONE", "PORTAL")) {
            for (String origin : List.of("OPERATOR", "CAMPAIGN", "SYSTEM")) {
                assertThat(collectionDenials(debtor, creditorB, channel, origin)).as(channel + "/" + origin)
                        .contains(DenialReason.PAYMENT_REVERSAL_HOLD);
            }
        }
        assertThat(collectionDenials(debtor, creditorA, "WHATSAPP", "OPERATOR"))
                .doesNotContain(DenialReason.PAYMENT_REVERSAL_HOLD);
        // código de acesso ao portal continua permitido
        assertThat(system(() -> contactGate.evaluate(new ContactRequest(debtor, creditorB, "EMAIL", null, null,
                "SYSTEM", null, null, "AUTHENTICATION"))).reasons()).doesNotContain(DenialReason.PAYMENT_REVERSAL_HOLD);

        // RS-111: o caso do credor B fica inelegível com o motivo próprio (relido do debt)
        awaitTrue("caso inelegível pela retenção", () -> asManager(() -> cases.findActive(debtor, portfolioB))
                .map(c -> c.eligibilityReasons().contains("PAYMENT_REVERSAL_HOLD")).orElse(false));
        // RS-112: sem negociação nem instrução de pagamento do título retido
        assertThat(system(() -> negotiationEligibility.check(creditorB, debtor, List.of(titleB))).ineligible()
                .get(titleB)).contains("title.reversal_hold");
        assertThatThrownBy(() -> asOperator(() -> instructions.create(new CreateInstructionCommand("PIX_IMMEDIATE", null, null,
                titleB, null, null, null, "WHATSAPP"), null))).isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "payment_instruction.reversal_hold");

        // RS-109: suspender e reativar o título não encerra a retenção
        system(() -> debtTitles.suspend(titleB, "Conferência interna", null));
        system(() -> debtTitles.reactivate(titleB, null));
        assertThat(system(() -> holds.isHeld(debtor, creditorB))).isTrue();

        // RS-120: o próximo informe do credor B para o devedor vai à revisão, qualquer valor
        PaymentView again = asOperator(() -> payments.registerExternal(toTitle(creditorB, debtor, titleB, "10.00",
                "CREDITOR_REPORT"), pdf(), null));
        assertThat(again.status()).isEqualTo("INFORMED");
        assertThat(count("select count(*) from payment.payment where id = ? and review_reason = 'RECENT_CREDITOR_REVERSAL'",
                again.id())).isEqualTo(1);
        PaymentView agencyAgain = asOperator(() -> payments.registerExternal(toTitle(creditorB, debtor, titleB, "10.00",
                "AGENCY_ACCOUNT"), pdf(), null));
        assertThat(agencyAgain.status()).isEqualTo("CONFIRMED");

        // aba "Estornos em conferência" e pendência: quem estornou não vê a pendência nem pode liberar
        HeldReversalItem row = asFinance2(() -> reversalHolds.search(pendingToday(), PageQuery.of(0, 20))).content()
                .getFirst();
        assertThat(row.hold().paymentId()).isEqualTo(paid.id());
        assertThat(row.amount()).isEqualByComparingTo("100.00");
        assertThat(row.channel()).isEqualTo("OPERATOR");
        assertThat(row.hold().canRelease()).isTrue();
        assertThat(row.hold().reversalsInWindow()).isEqualTo(1);
        assertThat(asFinance1(() -> reversalHolds.get(row.hold().id())).hold().canRelease()).isFalse();
        assertThat(system(() -> pendingWork.reversalHolds(null, finance2, Instant.now().plusSeconds(60))).total())
                .isEqualTo(1);
        assertThat(system(() -> pendingWork.reversalHolds(null, finance1, Instant.now())).total()).isZero();
        assertThat(system(() -> pendingWork.reversalHolds(null, operator, Instant.now())).total()).isZero();

        // RS-115: nem quem estornou nem quem informou liberam (o administrador também não, se tivesse estornado)
        long version = row.hold().version();
        assertThatThrownBy(() -> asFinance1(() -> reversalHolds.release(row.hold().id(), new ReleaseCommand(version,
                "CREDITOR_CONFIRMED_NOT_RECEIVED", "Conferido", true)))).isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "reversal_hold.self_release");
        // RS-116: justificativa (422) e confirmação (409)
        assertThatThrownBy(() -> asFinance2(() -> reversalHolds.release(row.hold().id(), new ReleaseCommand(version,
                "OTHER", " ", true)))).isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "reversal_hold.release_note_required");
        assertThatThrownBy(() -> asFinance2(() -> reversalHolds.release(row.hold().id(), new ReleaseCommand(version,
                "OTHER", "Conferido por telefone", false)))).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "reversal_hold.confirmation_required");
        assertThatThrownBy(() -> asFinance2(() -> reversalHolds.release(row.hold().id(), new ReleaseCommand(version + 7,
                "OTHER", "Conferido por telefone", true)))).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "reversal_hold.version_conflict");
        assertThatThrownBy(() -> asFinance2(() -> reversalHolds.release(row.hold().id(), new ReleaseCommand(version,
                "QUALQUER", "Conferido por telefone", true)))).isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "reversal_hold.release_reason_invalid");
        // RC2-03: versão velha e motivo inválido entram na métrica das recusas
        assertThat(meterRegistry.find("debt.reversal_hold.operations").tag("outcome", "refused_version").counter())
                .isNotNull();
        assertThat(meterRegistry.find("debt.reversal_hold.operations").tag("outcome", "refused_reason").counter())
                .isNotNull();
        HeldReversalItem released = asFinance2(() -> reversalHolds.release(row.hold().id(), new ReleaseCommand(version,
                "CREDITOR_CONFIRMED_NOT_RECEIVED", "Credor confirmou por telefone que não recebeu", true)));
        assertThat(released.hold().state()).isEqualTo("RELEASED");
        assertThat(count("select count(*) from shared.audit_event where action = 'reversal_hold.released' "
                + "and entity_id = ?", row.hold().id())).isEqualTo(1);
        // a justificativa fica na retenção (nunca na auditoria nem na resposta)
        assertThat(count("select count(*) from shared.audit_event where entity_id = ? "
                + "and coalesce(after_state::text, '') || coalesce(metadata::text, '') like '%telefone%'",
                row.hold().id())).isZero();
        assertThatThrownBy(() -> asFinance2(() -> reversalHolds.release(row.hold().id(), new ReleaseCommand(
                released.hold().version(), "OTHER", "de novo", true)))).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "reversal_hold.not_pending");

        // depois da liberação, a cobrança volta pelo caminho normal
        assertThat(collectionDenials(debtor, creditorB, "WHATSAPP", "OPERATOR"))
                .doesNotContain(DenialReason.PAYMENT_REVERSAL_HOLD);
        assertThat(system(() -> negotiationEligibility.check(creditorB, debtor, List.of(titleB))).ineligible()
                .getOrDefault(titleB, List.of())).doesNotContain("title.reversal_hold");
        awaitTrue("caso elegível de novo", () -> asManager(() -> cases.findActive(debtor, portfolioB))
                .map(c -> !c.eligibilityReasons().contains("PAYMENT_REVERSAL_HOLD")).orElse(false));
    }

    @Test
    void agreementReversalHoldsTheReopenedInstallmentOutOfTheBreakRule() {
        provision("retencao-acordo");
        AgreementView agreement = formalizeAgreement(3);
        UUID first = agreement.installments().getFirst().id();
        PaymentView paid = asOperator(() -> payments.registerExternal(new ExternalPaymentCommand(creditorA, debtor,
                agreement.installments().getFirst().amount(), paidAt, "PIX", "CREDITOR_DIRECT", null, null,
                new TargetInput("AGREEMENT", agreement.id(), List.of(first), null)), pdf(), null));
        assertThat(paid.status()).isEqualTo("CONFIRMED");
        settle();
        asFinance1(() -> payments.reverse(paid.id(), new ReverseCommand("OTHER", "Credor desfez o informe")));
        assertThat(count("select count(*) from debt.reversal_hold h join debt.reversal_hold_item i on i.hold_id = h.id "
                + "and i.kind = 'AGREEMENT' where h.payment_id = ? and i.item_id = ?", paid.id(), agreement.id()))
                .isEqualTo(1);
        assertThat(system(() -> holds.pendingInstallmentIds(agreement.id()))).containsExactly(first);
        assertThat(system(() -> holds.breakExemptInstallmentIds(agreement.id(), today, 3))).containsExactly(first);
        // títulos do acordo entram na retenção
        assertThat(system(() -> holds.heldTitleIds(creditorA, debtor))).contains(title60, title30);
        // instrução da parcela retida recusada; as demais parcelas seguem normais
        assertThatThrownBy(() -> asOperator(() -> instructions.create(new CreateInstructionCommand("PIX_IMMEDIATE",
                agreement.id(), first, null, null, null, null, "WHATSAPP"), null)))
                .hasFieldOrPropertyWithValue("code", "payment_instruction.reversal_hold");

        // depois da liberação, a parcela fica na carência normal antes de contar para a quebra
        UUID holdId = asFinance2(() -> reversalHolds.forPayment(paid.id())).getFirst().hold().id();
        long version = asFinance2(() -> reversalHolds.get(holdId)).hold().version();
        asFinance2(() -> reversalHolds.release(holdId, new ReleaseCommand(version, "PAYMENT_ON_WRONG_TITLE",
                "Pagamento era de outra parcela", true)));
        assertThat(system(() -> holds.pendingInstallmentIds(agreement.id()))).isEmpty();
        assertThat(system(() -> holds.breakExemptInstallmentIds(agreement.id(), today, 3))).containsExactly(first);
        assertThat(system(() -> holds.breakExemptInstallmentIds(agreement.id(), today.plusDays(4), 3))).isEmpty();
    }

    @Test
    void anonymizationEndsTheHoldWithoutResumingContactAndClearsTheReleaseNote(CapturedOutput output) {
        provision("retencao-lgpd");
        UUID[] other = debtorWithTitle(creditorB, portfolioB, 91, "Pessoa Ficticia Retida", "RET-1", "80.00");
        PaymentView paid = asOperator(() -> payments.registerExternal(toTitle(creditorB, other[0], other[1], "80.00",
                "CREDITOR_DIRECT"), pdf(), null));
        asFinance1(() -> payments.reverse(paid.id(), new ReverseCommand("OTHER", "Credor desfez o informe")));
        UUID holdId = system(() -> jdbcTemplate.queryForObject("select id from debt.reversal_hold where payment_id = ?",
                UUID.class, paid.id()));
        // encerra o título para poder anonimizar (sem cobrança em aberto)
        system(() -> debtTitles.cancel(other[1], "Credor desistiu da cobrança", null));
        system(() -> {
            debtorUseCases.anonymize(other[0], "Solicitação do titular");
            return null;
        });
        awaitTrue("retenção encerrada pela anonimização", () -> count(
                "select count(*) from debt.reversal_hold where id = ? and state = 'ENDED' "
                        + "and ended_reason = 'DEBTOR_ANONYMIZED'", holdId) == 1);
        assertThat(count("select count(*) from shared.audit_event where action = 'reversal_hold.ended' and entity_id = ?",
                holdId)).isEqualTo(1);
        assertThat(system(() -> holds.isHeld(other[0], creditorB))).isFalse();
        assertThat(output.getAll()).doesNotContain("Pessoa Ficticia Retida");
    }

    /** RS-109 pelo token {@code titles:write} (integração do credor) e RS-115 com o administrador que estornou. */
    @Test
    void integrationSuspendReactivateKeepsTheHoldAndTheAdminWhoReversedCannotRelease() {
        provision("retencao-token");
        PaymentView paid = asOperator(() -> payments.registerExternal(toTitle(creditorB, debtor, titleB, "100.00",
                "CREDITOR_DIRECT"), pdf(), null));
        asAdmin(() -> payments.reverse(paid.id(), new ReverseCommand("OTHER", "Credor desfez o informe")));
        Actor token = new Actor(Actor.ActorType.INTEGRATION, Ids.newId(), tenant, null, "integration:titles",
                EnumSet.copyOf(TokenScope.TITLES_WRITE.permissions()), CreditorScope.of(List.of(creditorB)));
        tenantExecution.callAs(token, () -> debtTitles.suspend(titleB, "Conferência do credor", null));
        tenantExecution.callAs(token, () -> debtTitles.reactivate(titleB, null));
        assertThat(system(() -> holds.isHeld(debtor, creditorB))).isTrue();

        HeldReversalItem row = asAdmin(() -> reversalHolds.forPayment(paid.id())).getFirst();
        assertThat(row.hold().canRelease()).isFalse();
        assertThatThrownBy(() -> asAdmin(() -> reversalHolds.release(row.hold().id(), new ReleaseCommand(
                row.hold().version(), "OTHER", "Conferido", true)))).isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "reversal_hold.self_release");
    }

    /** RS-113 e RS-121: o score não grava o estorno de pagamento ao credor; logs sem a justificativa nem o nome. */
    @Test
    void scoreIgnoresTheCreditorReversalAndLogsCarryNoPersonalData(CapturedOutput output) {
        provision("retencao-score");
        asManager(() -> scoringSettings.registerLegalBasis(new LegalBasisCommand("LEGITIMATE_INTEREST",
                "LIA-2026-07 aprovada pelo encarregado")));
        asManager(() -> scoringSettings.update(new SettingsCommand(30, 500, 50, 10, new BigDecimal("0.30"),
                BigDecimal.ONE, new BigDecimal("0.60"), 365, 1, 10, new BigDecimal("0.10"), new BigDecimal("0.25"),
                List.of(400, 250, 150, 75), new BigDecimal("0.30"), true)));
        PaymentView creditorPaid = asOperator(() -> payments.registerExternal(toTitle(creditorB, debtor, titleB, "100.00",
                "CREDITOR_DIRECT"), pdf(), null));
        PaymentView agencyPaid = asOperator(() -> payments.registerExternal(toTitle(creditorA, debtor, title30, "50.00",
                "AGENCY_ACCOUNT"), pdf(), null));
        asFinance1(() -> payments.reverse(creditorPaid.id(), new ReverseCommand("OTHER", "Credor desfez o informe")));
        asFinance1(() -> payments.reverse(agencyPaid.id(), new ReverseCommand("BANK_RETURN", "Devolvido pelo banco")));
        awaitConsumed("scoring.payment-reversed", 2);
        assertThat(count("select count(*) from scoring.case_fact where kind = 'PAYMENT_REVERSAL' and creditor_id = ?",
                creditorB)).isZero();
        assertThat(count("select count(*) from scoring.case_fact where kind = 'PAYMENT_REVERSAL' and creditor_id = ?",
                creditorA)).isEqualTo(1);

        UUID holdId = asFinance2(() -> reversalHolds.forPayment(creditorPaid.id())).getFirst().hold().id();
        long version = asFinance2(() -> reversalHolds.get(holdId)).hold().version();
        asFinance2(() -> reversalHolds.release(holdId, new ReleaseCommand(version, "OTHER",
                "Justificativa-secreta-XYZ conferida", true)));
        assertThat(output.getAll()).doesNotContain("Justificativa-secreta-XYZ").doesNotContain("Maria Devedora");
    }

    /** RC-02: o caso do coobrigado ativo, no mesmo credor, também é reavaliado na colocação e na liberação. */
    @Test
    void coobligorCaseFollowsTheHold() {
        provision("retencao-coobrigado");
        UUID batch = Ids.newId();
        UUID mainDebtor = system(() -> ingestion.upsertDebtor(new DebtorRecord(cpf(301), "Principal Ficticio",
                LocalDate.of(1980, 1, 1), List.of(), null)).id());
        UUID mainTitle = system(() -> ingestion.upsertTitle(new TitleRecord(creditorB, portfolioB, mainDebtor, "CO-1",
                new ContractRecord("CTR-CO", null, null, null), 1, today.minusDays(15), new BigDecimal("300.00"), null,
                null), batch).id());
        system(() -> ingestion.upsertObligationParty(new ObligationPartyRecord(creditorB, "CTR-CO",
                new DebtorRecord(cpf(302), "Avalista Ficticio", LocalDate.of(1980, 1, 1), List.of(), null),
                "GUARANTOR_AVAL", null)));
        UUID guarantor = system(() -> jdbcTemplate.queryForObject("select debtor_id from debt.obligation_party "
                + "where creditor_id = ?", UUID.class, creditorB));
        system(() -> ingestion.upsertTitle(new TitleRecord(creditorB, portfolioB, guarantor, "AV-1", null, 1,
                today.minusDays(15), new BigDecimal("200.00"), null, null), batch));
        system(() -> {
            ingestion.completeImport(new ImportSummary(batch, creditorB, portfolioB, 2, 0, 1));
            return null;
        });
        awaitTrue("caso do avalista aberto", () -> {
            drainJobs("collection.");
            return asManager(() -> cases.findActive(guarantor, portfolioB)).isPresent();
        });

        PaymentView paid = asOperator(() -> payments.registerExternal(toTitle(creditorB, mainDebtor, mainTitle, "50.00",
                "CREDITOR_DIRECT"), pdf(), null));
        asFinance1(() -> payments.reverse(paid.id(), new ReverseCommand("OTHER", "Credor desfez o informe")));
        assertThat(system(() -> holds.isHeld(guarantor, creditorB))).isTrue();
        awaitTrue("caso do avalista inelegível", () -> asManager(() -> cases.findActive(guarantor, portfolioB))
                .map(c -> c.eligibilityReasons().contains("PAYMENT_REVERSAL_HOLD")).orElse(false));

        UUID holdId = asFinance2(() -> reversalHolds.forPayment(paid.id())).getFirst().hold().id();
        long version = asFinance2(() -> reversalHolds.get(holdId)).hold().version();
        asFinance2(() -> reversalHolds.release(holdId, new ReleaseCommand(version, "OTHER", "Conferido", true)));
        awaitTrue("caso do avalista elegível de novo", () -> asManager(() -> cases.findActive(guarantor, portfolioB))
                .map(c -> !c.eligibilityReasons().contains("PAYMENT_REVERSAL_HOLD")).orElse(false));
    }

    /** RS-113 no job de quebra: sem quebra durante a retenção; depois da liberação e da carência, a regra volta. */
    @Test
    void breakJobSkipsTheHeldInstallmentUntilReleasedAndTheGracePassed() {
        provision("retencao-quebra");
        AgreementView agreement = formalizeAgreement(3);
        Assumptions.assumeTrue(agreement.breakRule().overdueInstallments() == 2,
                "cenário supõe a regra de quebra de duas parcelas");
        UUID first = agreement.installments().getFirst().id();
        PaymentView paid = asOperator(() -> payments.registerExternal(new ExternalPaymentCommand(creditorA, debtor,
                agreement.installments().getFirst().amount(), paidAt, "PIX", "CREDITOR_DIRECT", null, null,
                new TargetInput("AGREEMENT", agreement.id(), List.of(first), null)), pdf(), null));
        settle();
        asFinance1(() -> payments.reverse(paid.id(), new ReverseCommand("OTHER", "Credor desfez o informe")));
        settle();
        awaitTrue("parcela reaberta", () -> agreement(agreement.id()).installments().getFirst().paidAmount()
                .signum() == 0);
        LocalDate breakDay = agreement.installments().get(1).dueDate()
                .plusDays(agreement.breakRule().toleranceDays() + 1L);
        daily(breakDay);
        assertThat(agreement(agreement.id()).status()).isNotEqualTo("BROKEN");

        UUID holdId = asFinance2(() -> reversalHolds.forPayment(paid.id())).getFirst().hold().id();
        long version = asFinance2(() -> reversalHolds.get(holdId)).hold().version();
        asFinance2(() -> reversalHolds.release(holdId, new ReleaseCommand(version, "OTHER", "Conferido", true)));
        // na carência (hoje é o dia da liberação), a parcela ainda não conta
        assertThat(system(() -> holds.breakExemptInstallmentIds(agreement.id(), today, 3))).contains(first);
        // o dia simulado da quebra fica muito depois da carência: a regra volta a valer
        daily(breakDay);
        assertThat(agreement(agreement.id()).status()).isEqualTo("BROKEN");
    }

    private void daily(LocalDate day) {
        system(() -> {
            NegotiationTestHooks.daily(applicationContext, day);
            return null;
        });
    }

    /** Acordo dentro da alçada do operador só com os títulos informados (para dois acordos do mesmo par). */
    private AgreementView formalizeWith(List<UUID> titleIds, int installments) {
        UUID simulation = asOperator(() -> simulations.simulate(new SimulateCommand(creditorA, debtor, titleIds, null,
                null, null, List.of(new PlanInput(installments, null, null)), "PHONE")).id());
        UUID proposal = asOperator(() -> proposals.create(new CreateProposalCommand(simulation, 0, null,
                new PlanInput(installments, null, null), null, null, false, null)).id());
        asOperator(() -> proposals.accept(proposal, new AcceptCommand("PHONE", "call-1", null, null)));
        return asOperator(() -> proposals.formalize(proposal));
    }

    /**
     * Pagamento com alocações em dois acordos (sobra do acordo A realocada para o acordo B). Devolve {pagamento, A, B}.
     */
    private Object[] paymentOnTwoAgreements(String origin) {
        asAdmin(() -> paymentSettings.update(new PaymentSettingsUseCases.SettingsCommand(new BigDecimal("100000.00"),
                86_400, 30, 3)));
        AgreementView a = formalizeWith(List.of(title60), 3);
        AgreementView b = formalizeWith(List.of(title30), 3);
        BigDecimal extra = b.installments().getFirst().amount();
        PaymentView paid = asOperator(() -> payments.registerExternal(new ExternalPaymentCommand(creditorA, debtor,
                a.totalAmount().add(extra), paidAt, "PIX", origin, null, null,
                new TargetInput("AGREEMENT", a.id(), List.of(a.installments().getFirst().id()), null)), pdf(), null));
        assertThat(paid.status()).isEqualTo("CONFIRMED");
        settle();
        asFinance1(() -> payments.reallocate(paid.id(), new ReallocateCommand(null, extra,
                new TargetInput("AGREEMENT", b.id(), List.of(b.installments().getFirst().id()), null), null,
                "Sobra do acordo A para a primeira parcela do acordo B")));
        settle();
        assertThat(count("select count(distinct agreement_id) from payment.payment_allocation where payment_id = ? "
                + "and status = 'ACTIVE'", paid.id())).isEqualTo(2);
        return new Object[] {paid, a, b};
    }

    /** RC2-01: estorno de pagamento da assessoria com alocações em dois acordos passa, sem retenção. */
    @Test
    void agencyPaymentOnTwoAgreementsIsReversedWithoutHold() {
        provision("retencao-2acordos-ag");
        PaymentView paid = (PaymentView) paymentOnTwoAgreements("AGENCY_ACCOUNT")[0];
        asFinance1(() -> payments.reverse(paid.id(), new ReverseCommand("BANK_RETURN", "Devolvido pelo banco")));
        assertThat(asFinance1(() -> payments.get(paid.id())).status()).isEqualTo("REVERSED");
        assertThat(count("select count(*) from debt.reversal_hold where payment_id = ?", paid.id())).isZero();
    }

    /** RC2-01: pagamento recebido pelo credor em dois acordos: retenção com os dois e nenhum quebra. */
    @Test
    void creditorPaymentOnTwoAgreementsHoldsBothAndNeitherBreaks() {
        provision("retencao-2acordos-cr");
        Object[] scenario = paymentOnTwoAgreements("CREDITOR_DIRECT");
        PaymentView paid = (PaymentView) scenario[0];
        AgreementView a = (AgreementView) scenario[1];
        AgreementView b = (AgreementView) scenario[2];
        Assumptions.assumeTrue(b.breakRule().overdueInstallments() == 2, "cenário supõe a regra de quebra de duas parcelas");
        asFinance1(() -> payments.reverse(paid.id(), new ReverseCommand("OTHER", "Credor desfez o informe")));
        settle();
        assertThat(count("select count(*) from debt.reversal_hold h join debt.reversal_hold_item i on i.hold_id = h.id "
                + "and i.kind = 'AGREEMENT' where h.payment_id = ? and i.item_id in (?, ?)", paid.id(), a.id(), b.id()))
                .isEqualTo(2);
        assertThat(system(() -> holds.pendingInstallmentIds(a.id()))).isNotEmpty();
        assertThat(system(() -> holds.pendingInstallmentIds(b.id()))).contains(b.installments().getFirst().id());
        assertThat(system(() -> holds.heldTitleIds(creditorA, debtor))).contains(title60, title30);
        LocalDate day = b.installments().get(1).dueDate().plusDays(b.breakRule().toleranceDays() + 1L);
        daily(day);
        assertThat(agreement(a.id()).status()).isNotEqualTo("BROKEN");
        assertThat(agreement(b.id()).status()).isNotEqualTo("BROKEN");
    }
}
