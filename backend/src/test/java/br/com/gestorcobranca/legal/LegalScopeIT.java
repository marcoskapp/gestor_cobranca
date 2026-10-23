package br.com.gestorcobranca.legal;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.legal.application.port.in.LawFirmUseCases.LawFirmCommand;
import br.com.gestorcobranca.legal.application.port.in.LawFirmUseCases.LawFirmView;
import br.com.gestorcobranca.legal.application.port.in.LegalDeadlineUseCases.AgendaFilter;
import br.com.gestorcobranca.legal.application.port.in.LegalDeadlineUseCases.DeadlineCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalDeadlineUseCases.DeadlineView;
import br.com.gestorcobranca.legal.application.port.in.LegalDeadlineUseCases.DueDateQuery;
import br.com.gestorcobranca.legal.application.port.in.LegalDeadlineUseCases.SuspensionCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalDeadlineUseCases.SuspensionView;
import br.com.gestorcobranca.legal.application.port.in.LegalEligibilityUseCases.EligibilityPreview;
import br.com.gestorcobranca.legal.application.port.in.LegalEligibilityUseCases.RuleCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.AssignCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.MatterFilter;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.MatterView;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.ReferCommand;
import br.com.gestorcobranca.legal.application.service.LegalTestHooks;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.campaign.CadenceExhausted;
import br.com.gestorcobranca.shared.events.debt.TitlePrescriptionApproaching;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

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
 * Escopo e isolamento do jurídico: escritório parceiro restrito aos casos atribuídos (e sem acesso
 * com o escritório inativo), parâmetro "quem conduz", escopo por credor, RLS entre assessorias,
 * indicação manual (títulos prescritos, caso já aberto), indicação por alerta de prescrição
 * próxima e prazos (suspensão forense, alertas, prazo perdido e correção).
 */
class LegalScopeIT extends LegalTestSupport {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    @BeforeEach
    void setUp() {
        provision("lscope");
    }

    private MatterView refer(String cpf, String externalId, LocalDate due) {
        UUID debtor = debtor(cpf);
        load(creditorA, portfolioA, debtor, new LoadedTitle(externalId, "C-" + externalId, due, "1500.00"));
        CaseView c = activeCase(debtor, portfolioA);
        return asLawyer(() -> matters.refer(new ReferCommand(c.id(), Set.of(), "Indicação manual do gestor")));
    }

    @Test
    void partnerLawFirmSeesOnlyAssignedMattersWhileActive() {
        MatterView first = refer("526.018.159-06", "S-1", LocalDate.of(2026, 3, 10));
        MatterView second = refer("083.016.613-05", "S-2", LocalDate.of(2026, 3, 10));
        assertThat(first.origin()).isEqualTo("MANUAL");

        // padrão conservador: só jurídico interno
        assertThatThrownBy(() -> asManager(() -> matters.assign(first.id(), new AssignCommand("LAW_FIRM", null,
                Ids.newId())))).isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("legal_matter.law_firms_disabled");
        asManager(() -> matters.assign(second.id(), new AssignCommand("INTERNAL", lawyer, null)));
        allowLawFirms();
        LawFirmView firm = asManager(() -> firms.create(new LawFirmCommand("Silva & Souza Advogados", "11.444.777/0001-61",
                "OAB/SP 12.345", "contato@silvasouza.adv.br")));
        assertThat(firm.maskedCnpj()).isEqualTo("**.444.777/0001-**");
        assertThatThrownBy(() -> asManager(() -> firms.create(new LawFirmCommand("Outro", "11.444.777/0001-61", null,
                null)))).isInstanceOf(ConflictException.class).extracting("code").isEqualTo("law_firm.duplicate");
        // vínculo com escritório (GET /legal/me): ainda sem escritório, o advogado parceiro é interno
        assertThat(asPartner(() -> firms.me()).external()).isFalse();
        asManager(() -> firms.addMember(firm.id(), partner));
        assertThat(asPartner(() -> firms.me())).satisfies(me -> {
            assertThat(me.external()).isTrue();
            assertThat(me.lawFirmId()).isEqualTo(firm.id());
            assertThat(me.lawFirmName()).isEqualTo("Silva & Souza Advogados");
            assertThat(me.firmActive()).isTrue();
        });
        assertThat(asLawyer(() -> firms.me()).external()).isFalse();
        assertThatThrownBy(() -> asManager(() -> firms.addMember(firm.id(), partner)))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("law_firm.member_duplicate");
        assertThatThrownBy(() -> asManager(() -> matters.assign(second.id(), new AssignCommand("INTERNAL", partner, null))))
                .isInstanceOf(BusinessRuleException.class);
        asManager(() -> matters.assign(first.id(), new AssignCommand("LAW_FIRM", partner, firm.id())));

        // o advogado parceiro só alcança o caso do escritório
        assertThat(asPartner(() -> matters.search(new MatterFilter(null, null, null, null, null, null, null, null, null),
                new PageQuery(0, 20))).content()).extracting(MatterView::id).containsExactly(first.id());
        assertThat(asPartner(() -> matters.get(first.id())).lawFirmId()).isEqualTo(firm.id());
        assertThatThrownBy(() -> asPartner(() -> matters.get(second.id()))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> asPartner(() -> matters.collaterals(second.id())))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> asPartner(() -> hearings.listByMatter(second.id())))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(asPartner(() -> hearings.listByMatter(first.id()))).isEmpty();
        assertThatThrownBy(() -> asPartner(() -> deadlines.create(second.id(), deadline(LocalDate.now(ZONE), 5))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(count("select count(*) from shared.access_log where event_type = 'SCOPE_DENIED' and actor_id = ? "
                + "and details ->> 'scope' = 'law_firm'", partner)).isGreaterThanOrEqualTo(2);
        // configurações da assessoria são exclusivas de usuários internos
        assertThatThrownBy(() -> asPartner(() -> firms.list())).isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> asPartner(() -> eligibility.createRule(new RuleCommand(creditorA, null, "x", 1, null,
                false, false, null, null, false)))).isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> asPartner(() -> matters.assign(first.id(), new AssignCommand("INTERNAL", lawyer, null))))
                .isInstanceOf(ScopeAccessDeniedException.class);

        // prazos: o parceiro conduz o seu caso e a agenda só traz os seus
        asPartner(() -> deadlines.create(first.id(), deadline(LocalDate.now(ZONE), 5)));
        asLawyer(() -> deadlines.create(second.id(), deadline(LocalDate.now(ZONE), 5)));
        assertThat(asPartner(() -> deadlines.agenda(new AgendaFilter(null, Set.of("PENDING"), null, null, null),
                new PageQuery(0, 20))).content()).extracting(DeadlineView::matterId).containsExactly(first.id());
        assertThat(asLawyer(() -> deadlines.agenda(new AgendaFilter(null, Set.of("PENDING"), null, null, null),
                new PageQuery(0, 20))).totalElements()).isEqualTo(2);
        assertThat(asPartner(() -> matters.dashboard(null)).openMatters()).isEqualTo(1);

        // escopo por credor: usuário restrito ao credor B não vê casos do credor A
        assertThatThrownBy(() -> as(Ids.newId(), DefaultRoles.JURIDICO, CreditorScope.of(Set.of(creditorB)),
                () -> matters.get(first.id()))).isInstanceOf(ResourceNotFoundException.class);

        // escritório inativo: nenhum acesso
        asManager(() -> firms.setActive(firm.id(), false));
        assertThatThrownBy(() -> asPartner(() -> matters.get(first.id()))).isInstanceOf(ResourceNotFoundException.class);
        assertThat(asPartner(() -> matters.search(new MatterFilter(null, null, null, null, null, null, null, null, null),
                new PageQuery(0, 20))).content()).isEmpty();
        assertThat(asPartner(() -> firms.me())).satisfies(me -> {
            assertThat(me.external()).isTrue();
            assertThat(me.firmActive()).isFalse();
        });
    }

    @Test
    void exhaustedExtrajudicialCadenceRefersByRule() {
        UUID debtor = debtor("186.091.390-34");
        load(creditorA, portfolioA, debtor, new LoadedTitle("RG-1", "C-RG-1", LocalDate.of(2026, 3, 10), "900.00"));
        CaseView c = activeCase(debtor, portfolioA);
        asManager(() -> eligibility.createRule(new RuleCommand(creditorA, null, "Régua extrajudicial esgotada", null,
                null, false, false, null, null, true)));
        // a varredura diária não indica sem régua esgotada
        assertThat(LegalTestHooks.sweepEligibility(applicationContext, tenant)).isZero();

        // régua de lembrete (outra finalidade) é ignorada
        publishAndAwait(cadence(c, "REMINDER"), "legal.cadence-exhausted");
        assertThat(count("select count(*) from legal.cadence_exhaustion")).isZero();
        assertThat(count("select count(*) from legal.legal_matter where collection_case_id = ?", c.id())).isZero();

        CadenceExhausted exhausted = cadence(c, "EXTRAJUDICIAL_COLLECTION");
        publishAndAwait(exhausted, "legal.cadence-exhausted");
        MatterView matter = asLawyer(() -> matters.search(new MatterFilter(null, null, null, c.id(), null, null, null,
                null, true), new PageQuery(0, 5))).content().getFirst();
        assertThat(matter.origin()).isEqualTo("CADENCE_EXHAUSTED");
        assertThat(count("select count(*) from legal.cadence_exhaustion where collection_case_id = ? "
                + "and enrollment_id = ?", c.id(), exhausted.enrollmentId())).isEqualTo(1);
        assertThat(count("select count(*) from shared.audit_event where action = 'legal.cadence_exhausted'")).isEqualTo(1);

        // reentrega do mesmo evento (idempotente) e nova inscrição esgotada: nenhum caso jurídico a mais
        tenantExecution.inTenant(tenant, "test.publish", () -> publisher.publishEvent(exhausted));
        publishAndAwait(cadence(c, "EXTRAJUDICIAL_COLLECTION"), "legal.cadence-exhausted");
        assertThat(count("select count(*) from legal.legal_matter where collection_case_id = ?", c.id())).isEqualTo(1);
        assertThat(count("select count(*) from legal.cadence_exhaustion where collection_case_id = ?", c.id()))
                .isEqualTo(2);
        // registro imutável
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update legal.cadence_exhaustion set messages_sent = 0"))).isInstanceOf(DataAccessException.class);
    }

    private CadenceExhausted cadence(CaseView c, String purpose) {
        return new CadenceExhausted(Ids.newId(), tenant, Instant.now(), Ids.newId(), Ids.newId(), purpose, 1, c.id(),
                c.debtorId(), c.creditorId(), c.portfolioId(), 4, 1, 3, Instant.now().minus(30, ChronoUnit.DAYS));
    }

    @Test
    void rowLevelSecurityIsolatesTenants() {
        MatterView matter = refer("186.091.390-34", "R-1", LocalDate.of(2026, 3, 10));
        UUID tenantA = tenant;
        assertThat(count("select count(*) from legal.legal_matter_title where matter_id = ?", matter.id())).isEqualTo(1);
        provision("lother");
        assertThat(tenant).isNotEqualTo(tenantA);
        assertThatThrownBy(() -> asLawyer(() -> matters.get(matter.id()))).isInstanceOf(ResourceNotFoundException.class);
        assertThat(count("select count(*) from legal.legal_matter where id = ?", matter.id())).isZero();
        assertThat(count("select count(*) from legal.legal_matter_title where matter_id = ?", matter.id())).isZero();
        assertThat(count("select count(*) from legal.matter_stage_history where matter_id = ?", matter.id())).isZero();
        // inserção com tenant de outra assessoria é recusada pela política
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("""
                insert into legal.court_suspension (id, tenant_id, version, created_at, updated_at, start_date, end_date, reason)
                values (?, ?, 0, now(), now(), current_date, current_date, 'x')""", Ids.newId(), tenantA)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void manualReferralRespectsPrescriptionPolicyAndSingleOpenMatter() {
        UUID old = debtor("526.018.159-06");
        load(creditorA, portfolioA, old, new LoadedTitle("P-1", "C-P-1", LocalDate.of(2019, 1, 10), "900.00"));
        CaseView prescribed = activeCase(old, portfolioA);
        EligibilityPreview preview = asLawyer(() -> eligibility.evaluate(prescribed.id()));
        assertThat(preview.manual().eligible()).isFalse();
        assertThat(preview.manual().unmet()).containsExactly("ALL_TITLES_PRESCRIBED");
        assertThatThrownBy(() -> asLawyer(() -> matters.refer(new ReferCommand(prescribed.id(), Set.of(), "Tentativa"))))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("legal_matter.not_eligible");

        MatterView open = refer("083.016.613-05", "P-2", LocalDate.of(2026, 3, 10));
        assertThatThrownBy(() -> asLawyer(() -> matters.refer(new ReferCommand(open.collectionCaseId(), Set.of(),
                "De novo")))).isInstanceOf(ConflictException.class).extracting("code").isEqualTo("legal_matter.already_open");
        assertThatThrownBy(() -> asLawyer(() -> matters.refer(new ReferCommand(open.collectionCaseId(), Set.of(), " "))))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void prescriptionAlertReferralsByRule() {
        UUID debtor = debtor("186.091.390-34");
        LocalDate due = LocalDate.now(ZONE).minusYears(5).plusDays(40);
        List<UUID> titles = load(creditorA, portfolioA, debtor, new LoadedTitle("N-1", "C-N-1", due, "700.00"));
        CaseView c = activeCase(debtor, portfolioA);
        asManager(() -> eligibility.createRule(new RuleCommand(creditorA, portfolioA, "Prescrição em 90 dias", null, null,
                false, false, null, 90, false)));
        publishAndAwait(new TitlePrescriptionApproaching(Ids.newId(), tenant, Instant.now(), titles.getFirst(), debtor,
                creditorA, portfolioA, null, "CONTRACT", false, "COLLECTION", due.plusYears(5), 40, false,
                new BigDecimal("700.00")), "legal.prescription-approaching");
        MatterView matter = asLawyer(() -> matters.search(new MatterFilter(null, null, null, c.id(), null, null, null,
                null, true), new PageQuery(0, 5))).content().getFirst();
        assertThat(matter.origin()).isEqualTo("PRESCRIPTION_ALERT");
        assertThat(matter.flags()).contains("PRESCRIPTION_NEAR");

        // um novo alerta do mesmo título sinaliza o caso jurídico aberto (sem nova indicação)
        publishAndAwait(new TitlePrescriptionApproaching(Ids.newId(), tenant, Instant.now(), titles.getFirst(), debtor,
                creditorA, portfolioA, null, "CONTRACT", false, "COLLECTION", due.plusYears(5), 39, false,
                new BigDecimal("700.00")), "legal.prescription-approaching");
        assertThat(count("select count(*) from legal.legal_matter where collection_case_id = ?", c.id())).isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'legal.prescription_alert'"))
                .isEqualTo(1);
    }

    @Test
    void deadlinesFollowSuspensionsAlertAndMissed() {
        MatterView matter = refer("526.018.159-06", "D-1", LocalDate.of(2026, 3, 10));
        LocalDate today = LocalDate.now(ZONE);
        DeadlineView soon = asLawyer(() -> deadlines.create(matter.id(), deadline(today, 10)));
        assertThat(soon.dueDate()).isEqualTo(asLawyer(() -> deadlines.preview(new DueDateQuery(today, null, 10, null,
                null, null))).dueDate());

        SuspensionView suspension = asManager(() -> deadlines.addSuspension(new SuspensionCommand(today.plusDays(1),
                today.plusDays(7), null, "Indisponibilidade do sistema do tribunal")));
        DeadlineView moved = asLawyer(() -> deadlines.listByMatter(matter.id())).getFirst();
        assertThat(moved.dueDate()).isAfter(soon.dueDate());
        assertThat(count("select count(*) from shared.audit_event where action = 'legal_deadline.rescheduled' "
                + "and entity_id = ?", soon.id())).isEqualTo(1);
        assertThatThrownBy(() -> asPartner(() -> deadlines.addSuspension(new SuspensionCommand(today, today, null, "x"))))
                .isInstanceOf(ScopeAccessDeniedException.class);
        asManager(() -> {
            deadlines.removeSuspension(suspension.id(), "Cadastrada por engano");
            return null;
        });
        assertThat(asLawyer(() -> deadlines.listByMatter(matter.id())).getFirst().dueDate()).isEqualTo(soon.dueDate());

        // prazo já vencido → perdido pela varredura; cumprimento até o vencimento corrige
        DeadlineView past = asLawyer(() -> deadlines.create(matter.id(), deadline(today.minusDays(30), 5)));
        DeadlineView tomorrow = asLawyer(() -> deadlines.create(matter.id(), new DeadlineCommand("PAYMENT",
                "Recolher custas", null, null, null, null, null, "FIXED_DATE", nextBusinessDay(today), null)));
        system(() -> LegalTestHooks.scanDeadlines(applicationContext));
        List<DeadlineView> scanned = asLawyer(() -> deadlines.listByMatter(matter.id()));
        assertThat(scanned).filteredOn(d -> d.id().equals(past.id())).singleElement()
                .extracting(DeadlineView::status).isEqualTo("MISSED");
        assertThat(scanned).filteredOn(d -> d.id().equals(tomorrow.id())).singleElement()
                .extracting(DeadlineView::lastAlertLevel).isNotNull();
        assertThat(count("select count(*) from shared.business_event where event_type = 'legal.deadline_missed'"))
                .isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'legal.deadline_approaching'"))
                .isGreaterThanOrEqualTo(1);
        // a segunda varredura não repete alertas
        system(() -> LegalTestHooks.scanDeadlines(applicationContext));
        assertThat(count("select count(*) from shared.business_event where event_type = 'legal.deadline_missed'"))
                .isEqualTo(1);
        assertThatThrownBy(() -> asLawyer(() -> deadlines.complete(past.id(), today, null)))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("legal_deadline.missed");
        DeadlineView corrected = asLawyer(() -> deadlines.complete(past.id(), past.dueDate(), "Protocolo no prazo"));
        assertThat(corrected.status()).isEqualTo("MET");
    }

    private static DeadlineCommand deadline(LocalDate start, int days) {
        return new DeadlineCommand("RESPONSE", "Manifestação", null, null, start, "INTIMATION", days, "BUSINESS_DAYS", null,
                null);
    }

    private static LocalDate nextBusinessDay(LocalDate d) {
        LocalDate next = d.plusDays(1);
        while (next.getDayOfWeek().getValue() >= 6) {
            next = next.plusDays(1);
        }
        return next;
    }
}
