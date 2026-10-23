package br.com.gestorcobranca.legal;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.legal.application.port.in.CreditorAuthorizationUseCases.AuthorizationView;
import br.com.gestorcobranca.legal.application.port.in.CreditorAuthorizationUseCases.DecisionCommand;
import br.com.gestorcobranca.legal.application.port.in.ExtrajudicialMeasureUseCases.MeasureCommand;
import br.com.gestorcobranca.legal.application.port.in.ExtrajudicialMeasureUseCases.MeasureView;
import br.com.gestorcobranca.legal.application.port.in.ExtrajudicialMeasureUseCases.StatusCommand;
import br.com.gestorcobranca.legal.application.port.in.ExtrajudicialMeasureUseCases.SubmitCommand;
import br.com.gestorcobranca.legal.application.port.in.HearingUseCases.HearingCommand;
import br.com.gestorcobranca.legal.application.port.in.HearingUseCases.HearingView;
import br.com.gestorcobranca.legal.application.port.in.LawsuitUseCases.LawsuitCommand;
import br.com.gestorcobranca.legal.application.port.in.LawsuitUseCases.LawsuitView;
import br.com.gestorcobranca.legal.application.port.in.LawsuitUseCases.MovementCommand;
import br.com.gestorcobranca.legal.application.port.in.LawsuitUseCases.MovementResult;
import br.com.gestorcobranca.legal.application.port.in.LegalDeadlineUseCases.DeadlineCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalDeadlineUseCases.DeadlineView;
import br.com.gestorcobranca.legal.application.port.in.LegalEligibilityUseCases.RuleCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalExpenseUseCases.ExpenseCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.MatterFilter;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.MatterSheet;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.MatterView;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.TransitionCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalViabilityUseCases.AssessmentCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalViabilityUseCases.AssessmentInput;
import br.com.gestorcobranca.legal.application.port.in.LegalViabilityUseCases.AssessmentView;
import br.com.gestorcobranca.legal.application.port.in.LegalViabilityUseCases.ChecklistEntry;
import br.com.gestorcobranca.legal.application.port.in.LegalViabilityUseCases.ViabilityResultView;
import br.com.gestorcobranca.legal.application.service.LegalTestHooks;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.negotiation.AgreementStatusChanged;
import br.com.gestorcobranca.shared.paging.PageQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fluxo completo do jurídico contra PostgreSQL real: indicação pela regra (título prescrito
 * excluído), triagem e viabilidade, autorização do credor com evidência, protesto (interrupção da
 * prescrição no debt e retenção LEGAL do caso no collection), custas com orçamento, ajuizamento
 * com número CNJ, citação (interrupção e IN_PROGRESS), prazo em dias úteis, audiência, acordo
 * judicial por evento (homologação, quebra e retomada, quitação) e encerramento com baixa.
 */
class LegalFlowIT extends LegalTestSupport {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    @BeforeEach
    void setUp() {
        provision("legal");
    }

    @Test
    void ruleReferralToJudicialAgreementAndClosure() {
        UUID debtor = debtor("526.018.159-06");
        List<UUID> titles = load(creditorA, portfolioA, debtor,
                new LoadedTitle("T-1", "CCB-1", LocalDate.of(2026, 3, 11), "1000.00"),
                new LoadedTitle("T-2", "CCB-1", LocalDate.of(2026, 4, 11), "2000.00"),
                new LoadedTitle("T-OLD", "CT-OLD", LocalDate.of(2020, 1, 10), "300.00"));
        executiveContract(creditorA, "CCB-1");
        UUID collateral = vehicleCollateral(creditorA, "CCB-1", "20000.00");
        CaseView collectionCase = activeCase(debtor, portfolioA);
        assertThat(collectionCase.status()).isEqualTo("OPEN");

        // 1. indicação automática pela regra (atraso ≥ 90 dias); o título prescrito fica de fora
        asManager(() -> eligibility.createRule(new RuleCommand(creditorA, null, "Atraso 90+", 90, null, false, false,
                null, null, false)));
        assertThat(LegalTestHooks.sweepEligibility(applicationContext, tenant)).isEqualTo(1);
        assertThat(LegalTestHooks.sweepEligibility(applicationContext, tenant)).as("um caso jurídico por caso").isZero();
        MatterView matter = asLawyer(() -> matters.search(new MatterFilter(null, null, debtor, null, null, null, null,
                null, true), new PageQuery(0, 10))).content().getFirst();
        assertThat(matter.stage()).isEqualTo("ELIGIBLE");
        assertThat(matter.origin()).isEqualTo("RULE");
        assertThat(matter.titleIds()).containsExactlyInAnyOrder(titles.get(0), titles.get(1));
        assertThat(matter.flags()).contains("PRESCRIBED_TITLES_EXCLUDED", "PRESCRIPTION_RULE_UNVALIDATED");
        assertThat(matter.debtAmount()).isEqualByComparingTo("3000.00");
        assertThat(count("select count(*) from shared.business_event where event_type = 'legal.matter_referred' "
                + "and reference_id = ?", matter.id())).isEqualTo(1);
        assertThat(count("select count(*) from shared.audit_event where action = 'legal_matter.referred' and entity_id = ?",
                matter.id())).isEqualTo(1);
        UUID id = matter.id();

        // 2. aceite (LEGAL_AUTHORIZE) e viabilidade
        assertThatThrownBy(() -> asPartner(() -> matters.transition(id, new TransitionCommand("UNDER_REVIEW", "aceite",
                null, null)))).isInstanceOf(RuntimeException.class);
        asLawyer(() -> matters.transition(id, new TransitionCommand("UNDER_REVIEW", "Indicação aceita", null, null)));
        ViabilityResultView preview = asLawyer(() -> viability.preview(id, new AssessmentInput(null, null,
                new BigDecimal("80"), new BigDecimal("1500.00"))));
        assertThat(preview.debtAmount()).isEqualByComparingTo("3000.00");
        assertThat(preview.collateralValue()).isEqualByComparingTo("20000.00");
        assertThat(preview.estimatedRecoverable()).isEqualByComparingTo("2400.00");
        assertThat(preview.availableRoutes()).contains("EXECUTION", "SEARCH_AND_SEIZURE", "PROTEST");
        assertThat(preview.suggestedRoutes()).first().isEqualTo("SEARCH_AND_SEIZURE");
        assertThat(preview.suggestedChecklist()).extracting(ChecklistEntry::code).contains("CCB_STATEMENT",
                "COLLATERAL_REGISTRATION");
        List<ChecklistEntry> checklist = preview.suggestedChecklist().stream()
                .map(c -> new ChecklistEntry(c.code(), c.label(), "PRESENT", c.required())).toList();
        assertThatThrownBy(() -> asLawyer(() -> viability.record(id, new AssessmentCommand(new AssessmentInput(null, null,
                new BigDecimal("80"), new BigDecimal("1500.00")), true, List.of("COLLECTION_ACTION", "BANKRUPTCY"),
                checklist, null, null)))).isInstanceOf(BusinessRuleException.class);
        AssessmentView assessment = asLawyer(() -> viability.record(id, new AssessmentCommand(new AssessmentInput(null,
                null, new BigDecimal("80"), new BigDecimal("1500.00")), true, List.of("EXECUTION", "PROTEST"), checklist,
                "Veículo localizado", "Execução com protesto prévio")));
        assertThat(assessment.viable()).isTrue();
        assertThat(asLawyer(() -> matters.get(id)).primaryRoute()).isEqualTo("EXECUTION");

        // 3. autorização do credor: solicitação automática e concessão com evidência
        asLawyer(() -> matters.transition(id, new TransitionCommand("AWAITING_AUTHORIZATION", "Viável", null, null)));
        AuthorizationView pending = asLawyer(() -> authorizations.list(id)).getFirst();
        assertThat(pending.status()).isEqualTo("REQUESTED");
        assertThat(pending.requestedRoutes()).containsExactlyInAnyOrder("EXECUTION", "PROTEST");
        assertThat(activeCase(debtor, portfolioA).status()).as("triagem não retém o caso").isEqualTo("OPEN");
        UUID evidence = upload(lawyer, DefaultRoles.JURIDICO, id, "AUTHORIZATION_EVIDENCE", "AUTHORIZATION", pending.id())
                .id();
        LocalDate today = LocalDate.now(ZONE);
        assertThatThrownBy(() -> asLawyer(() -> authorizations.decide(pending.id(), new DecisionCommand("GRANTED",
                Set.of("EXECUTION"), new BigDecimal("1500.00"), today, "EMAIL", "Gerente do credor", null, null))))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("legal_authorization.evidence_required");
        AuthorizationView granted = asLawyer(() -> authorizations.decide(pending.id(), new DecisionCommand("GRANTED",
                Set.of("EXECUTION", "PROTEST"), new BigDecimal("1500.00"), today, "EMAIL", "Gerente do credor", evidence,
                "Aprovado")));
        assertThat(granted.status()).isEqualTo("GRANTED");
        assertThat(count("select count(*) from shared.business_event where event_type = 'legal.authorization_decided'"))
                .isEqualTo(1);

        // 4. protesto: medida → EXTRAJUDICIAL (retenção LEGAL no collection); lavratura interrompe a prescrição
        MeasureView protest = asLawyer(() -> measures.register(id, new MeasureCommand("PROTEST", Set.of(), null, null,
                "1º Tabelião de Protesto", null, null)));
        assertThat(protest.amount()).isEqualByComparingTo("3000.00");
        assertThat(protest.provider()).isEqualTo("manual");
        assertThat(asLawyer(() -> matters.get(id)).stage()).isEqualTo("EXTRAJUDICIAL");
        assertThat(activeCase(debtor, portfolioA).status()).isEqualTo("LEGAL");
        assertThat(asLawyer(() -> matters.get(id)).collectionHold()).isTrue();
        assertThatThrownBy(() -> asLawyer(() -> measures.dispatch(protest.id())))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("legal.provider_manual");
        asLawyer(() -> measures.submit(protest.id(), new SubmitCommand(today, "PROT-123")));
        upload(lawyer, DefaultRoles.JURIDICO, id, "PROTEST_INSTRUMENT", "MEASURE", protest.id());
        asLawyer(() -> measures.changeStatus(protest.id(), new StatusCommand("EFFECTIVE", today, "Protesto lavrado")));
        assertThat(count("select count(*) from debt.prescription_interruption where origin = 'LEGAL' and type = 'PROTEST' "
                + "and origin_reference_id = ? and evidence_sha256 is not null", protest.id())).isEqualTo(2);

        // 5. custas: só o credor adianta (padrão) e dentro do orçamento aprovado
        assertThatThrownBy(() -> asLawyer(() -> expenses.record(id, new ExpenseCommand("REGISTRY_FEES", null,
                new BigDecimal("100.00"), today, "AGENCY", true, null, null))))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("legal_expense.advancer_not_allowed");
        asLawyer(() -> expenses.record(id, new ExpenseCommand("REGISTRY_FEES", null, new BigDecimal("1000.00"), today,
                "CREDITOR", true, null, null)));
        assertThatThrownBy(() -> asLawyer(() -> expenses.record(id, new ExpenseCommand("COURT_FEES", null,
                new BigDecimal("600.00"), today, "CREDITOR", true, null, null))))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("legal_expense.budget_exceeded");
        assertThat(asLawyer(() -> matters.get(id)).expensesTotal()).isEqualByComparingTo("1000.00");

        // 6. ajuizamento com número CNJ validado → FILED
        String cnj = cnj(today.getYear());
        assertThatThrownBy(() -> asLawyer(() -> lawsuits.register(id, new LawsuitCommand(cnj, "MONITORY_ACTION", null,
                null, null, null, today, null, null)))).isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("lawsuit.not_authorized");
        assertThatThrownBy(() -> asLawyer(() -> lawsuits.register(id, new LawsuitCommand(cnj.substring(0, 8) + "99"
                + cnj.substring(10), "EXECUTION", null, null, null, null, today, null, null))))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("lawsuit.cnj_invalid");
        LawsuitView lawsuit = asLawyer(() -> lawsuits.register(id, new LawsuitCommand(cnj, "EXECUTION",
                "Execução de título extrajudicial", null, Set.of(), new BigDecimal("3000.00"), today, "TJSP",
                "1ª Vara Cível")));
        assertThat(lawsuit.courtCode()).isEqualTo("8.26");
        assertThat(lawsuit.cnjFormatted()).isEqualTo(cnj);
        assertThat(asLawyer(() -> matters.get(id)).stage()).isEqualTo("FILED");
        assertThatThrownBy(() -> asLawyer(() -> lawsuits.register(id, new LawsuitCommand(cnj, "EXECUTION", null, null,
                null, null, today, null, null)))).isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("lawsuit.duplicate");
        assertThatThrownBy(() -> asLawyer(() -> lawsuits.synchronize(lawsuit.id())))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("legal.provider_manual");

        // 7. citação: interrupção da prescrição, IN_PROGRESS e prazo de 15 dias úteis
        MovementResult citation = asLawyer(() -> lawsuits.recordMovement(lawsuit.id(), new MovementCommand(today,
                "CITATION", "Citação positiva", null, null, null, new DeadlineCommand("RESPONSE",
                "Prazo para embargos", null, null, today, null, 15, null, null, null))));
        assertThat(citation.prescriptionInterruptions()).isEqualTo(2);
        assertThat(citation.matter().stage()).isEqualTo("IN_PROGRESS");
        DeadlineView embargos = citation.deadline();
        assertThat(embargos.status()).isEqualTo("PENDING");
        assertThat(embargos.courtCode()).isEqualTo("8.26");
        assertThat(ChronoUnit.DAYS.between(today, embargos.dueDate())).isGreaterThanOrEqualTo(19);
        assertThat(embargos.businessDaysRemaining()).isBetween(14, 15);
        assertThat(count("select count(*) from debt.prescription_interruption where origin = 'LEGAL' and type = 'CITATION'"))
                .isEqualTo(2);
        HearingView hearing = asLawyer(() -> hearings.schedule(lawsuit.id(), new HearingCommand("CONCILIATION",
                Instant.now().plus(10, ChronoUnit.DAYS), "VIDEO", null, "https://audiencia.tjsp.jus.br/sala/1",
                Set.of(lawyer), "Preposto do credor")));
        assertThat(hearing.status()).isEqualTo("SCHEDULED");
        assertThatThrownBy(() -> asLawyer(() -> hearings.schedule(lawsuit.id(), new HearingCommand("CONCILIATION",
                Instant.now().plus(10, ChronoUnit.DAYS), "VIDEO", null, "http://inseguro", Set.of(), null))))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("legal_hearing.link_invalid");

        // ficha: audiências de todos os processos do caso e garantias que cobrem os títulos indicados
        assertThat(asLawyer(() -> hearings.listByMatter(id))).extracting(HearingView::id).containsExactly(hearing.id());
        MatterSheet filed = asLawyer(() -> matters.sheet(id));
        assertThat(filed.hearings()).extracting(HearingView::id).containsExactly(hearing.id());
        assertThat(filed.collaterals()).singleElement().satisfies(k -> {
            assertThat(k.collateralId()).isEqualTo(collateral);
            assertThat(k.type()).isEqualTo("VEHICLE_FIDUCIARY_ALIENATION");
            assertThat(k.assetSummary()).contains("ABC1D23");
            assertThat(k.estimatedValue()).isEqualByComparingTo("20000.00");
            assertThat(k.titleIds()).containsExactlyInAnyOrder(titles.get(0), titles.get(1));
        });
        assertThat(asLawyer(() -> matters.collaterals(id))).extracting(c -> c.collateralId()).containsExactly(collateral);

        // 8. acordo judicial pelo evento do negotiation: SETTLED_IN_COURT com prazo de homologação; quebra retoma
        UUID agreement = Ids.newId();
        publishAndAwait(agreementEvent(agreement, collectionCase, titles, "FORMALIZED", null), "legal.agreement-status");
        MatterView settled = asLawyer(() -> matters.get(id));
        assertThat(settled.stage()).isEqualTo("SETTLED_IN_COURT");
        assertThat(settled.agreementId()).isEqualTo(agreement);
        assertThat(asLawyer(() -> deadlines.listByMatter(id))).extracting(DeadlineView::kind).contains("FILING");
        publishAndAwait(agreementEvent(agreement, collectionCase, titles, "BROKEN", "FORMALIZED"), "legal.agreement-status");
        assertThat(asLawyer(() -> matters.get(id)).stage()).isEqualTo("IN_PROGRESS");
        assertThat(count("select count(*) from shared.business_event where event_type = 'legal.agreement_broken'"))
                .isEqualTo(1);
        assertThat(count("select count(*) from legal.agreement_record where agreement_id = ? and broken", agreement))
                .isEqualTo(1);

        UUID second = Ids.newId();
        publishAndAwait(agreementEvent(second, collectionCase, titles, "FORMALIZED", null), "legal.agreement-status");
        assertThat(asLawyer(() -> matters.get(id)).stage()).isEqualTo("SETTLED_IN_COURT");
        publishAndAwait(agreementEvent(second, collectionCase, titles, "SETTLED", "IN_PAYMENT"), "legal.agreement-status");

        // 9. encerramento: AGREEMENT_FULFILLED, retenção retirada, prazos cancelados e baixa do protesto
        MatterView closed = asLawyer(() -> matters.get(id));
        assertThat(closed.stage()).isEqualTo("CLOSED");
        assertThat(closed.closureReason()).isEqualTo("AGREEMENT_FULFILLED");
        assertThat(closed.collectionHold()).isFalse();
        awaitTrue("caso de cobrança fora do jurídico",
                () -> !"LEGAL".equals(system(() -> cases.get(collectionCase.id())).status()));
        List<DeadlineView> after = asLawyer(() -> deadlines.listByMatter(id));
        assertThat(after).filteredOn(d -> d.kind().equals("RESPONSE")).singleElement()
                .extracting(DeadlineView::status).isEqualTo("CANCELLED");
        assertThat(after).filteredOn(d -> d.kind().equals("RESTRICTION_REMOVAL")).singleElement()
                .satisfies(d -> {
                    assertThat(d.status()).isEqualTo("PENDING");
                    assertThat(d.measureId()).isEqualTo(protest.id());
                });
        asLawyer(() -> measures.changeStatus(protest.id(), new StatusCommand("REMOVED", today, "Baixa após quitação")));
        assertThat(asLawyer(() -> deadlines.listByMatter(id))).filteredOn(d -> d.kind().equals("RESTRICTION_REMOVAL"))
                .singleElement().extracting(DeadlineView::status).isEqualTo("MET");

        MatterSheet sheet = asLawyer(() -> matters.sheet(id));
        assertThat(sheet.debtor().maskedDocument()).doesNotContain("526.018");
        assertThat(sheet.history()).extracting(h -> h.toStage()).containsExactly("ELIGIBLE", "UNDER_REVIEW",
                "AWAITING_AUTHORIZATION", "EXTRAJUDICIAL", "FILED", "IN_PROGRESS", "SETTLED_IN_COURT", "IN_PROGRESS",
                "SETTLED_IN_COURT", "CLOSED");
        assertThat(sheet.documents()).hasSize(2);
        assertThat(count("select count(*) from shared.business_event where event_type = 'legal.matter_closed'"))
                .isEqualTo(1);
        var dashboard = asLawyer(() -> matters.dashboard(null));
        assertThat(dashboard.stages()).extracting(s -> s.stage()).contains("CLOSED");
        assertThat(dashboard.expensesTotal()).isEqualByComparingTo("1000.00");
        assertThatThrownBy(() -> asLawyer(() -> matters.transition(id, new TransitionCommand("UNDER_REVIEW", "reabrir",
                null, null)))).isInstanceOf(ConflictException.class);
    }

    private AgreementStatusChanged agreementEvent(UUID agreementId, CaseView c, List<UUID> titles, String status,
                                                  String previous) {
        return new AgreementStatusChanged(Ids.newId(), tenant, Instant.now(), agreementId, c.id(), c.debtorId(),
                c.creditorId(), c.portfolioId(), titles.subList(0, 2), previous, status, new BigDecimal("2500.00"), null);
    }

    /** Número CNJ válido do ano informado (TJSP, foro central). */
    static String cnj(int year) {
        String sequential = "0001234";
        String dd = br.com.gestorcobranca.legal.domain.model.CnjNumber.checkDigitsFor(sequential, String.valueOf(year),
                "8", "26", "0100");
        return sequential + "-" + dd + "." + year + ".8.26.0100";
    }
}
