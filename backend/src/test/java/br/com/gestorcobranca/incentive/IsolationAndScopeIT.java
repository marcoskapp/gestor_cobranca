package br.com.gestorcobranca.incentive;

import br.com.gestorcobranca.identity.application.port.in.WorkforceQueries;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases;
import br.com.gestorcobranca.incentive.application.port.in.PerformanceQueries;
import br.com.gestorcobranca.incentive.application.port.in.PerformanceQueries.IndicatorQuery;
import br.com.gestorcobranca.incentive.application.port.in.PerformanceQueries.IndicatorReport;
import br.com.gestorcobranca.incentive.application.port.in.PerformanceQueries.IndicatorRow;
import br.com.gestorcobranca.incentive.application.port.in.PeriodUseCases;
import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases;
import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases.CriterionInput;
import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases.EvaluationCommand;
import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases.FormCommand;
import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases.OccurrenceCommand;
import br.com.gestorcobranca.incentive.application.port.in.StatementUseCases;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.QualityModifiers;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.RateTable;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.RateTier;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.RecoveryRateComponent;
import br.com.gestorcobranca.incentive.domain.model.plan.TierMode;
import br.com.gestorcobranca.incentive.application.port.in.StatementUseCases.StatementSummary;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.collection.InteractionRecorded;
import br.com.gestorcobranca.shared.events.telephony.CallCompleted;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.format.support.FormattingConversionService;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import br.com.gestorcobranca.incentive.domain.model.plan.StandardPlans;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.dao.DataAccessException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Isolamento entre assessorias (RLS forçado), escopo próprio/equipe/tudo nos indicadores,
 * portão de monitoria com recálculo, zerador, simulação de plano e tempo logado das sessões.
 */
class IsolationAndScopeIT extends IncentiveTestSupport {

    @Autowired
    PerformanceQueries performance;
    @Autowired
    StatementUseCases statements;
    @Autowired
    PeriodUseCases periods;
    @Autowired
    QualityUseCases quality;
    @Autowired
    CommissionPlanUseCases plans;
    @Autowired
    WorkforceQueries workforce;
    @Autowired
    @Qualifier("mvcConversionService")
    FormattingConversionService mvcConversionService;
    @Autowired
    JsonCodec json;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void tenantsAreIsolatedByRowLevelSecurity() {
        provisionTenant("iso-a");
        UUID tenantA = tenant;
        UUID agreement = Ids.newId();
        publishAndAwait(attribution(agreement, Ids.newId(), "HUMAN", List.of(closer(operatorX)), "1000.00", 10, null,
                at(2026, 8, 3)), "incentive.attribution-recorded");
        publishAndAwait(payment(Ids.newId(), agreement, "500.00", at(2026, 8, 4)), "incentive.payment-confirmed");
        as(manager, DefaultRoles.GESTOR, () -> periods.close(YearMonth.of(2026, 8)));
        UUID statementA = as(manager, DefaultRoles.GESTOR, () -> statements.list(YearMonth.of(2026, 8), operatorX, null,
                PageQuery.of(0, 5))).content().getFirst().id();
        UUID managerA = manager;

        provisionTenant("iso-b");
        for (String table : List.of("commission_ledger_entry", "agreement_attribution", "payment_fact",
                "commission_statement", "performance_fact", "commission_period")) {
            assertThat(count("select count(*) from incentive." + table)).as(table).isZero();
        }
        assertThatThrownBy(() -> as(manager, DefaultRoles.GESTOR, () -> statements.get(statementA)))
                .isInstanceOf(ResourceNotFoundException.class);
        // gravação com tenant de outra assessoria é recusada pelo banco
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("""
                insert into incentive.logged_time_daily (tenant_id, fact_date, user_id, seconds)
                values (?, date '2026-08-01', ?, 100)""", tenantA, managerA)))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("row-level security");
        // sem tenant no contexto, nada é visível
        assertThat(jdbcTemplate.queryForObject("select count(*) from incentive.commission_ledger_entry", Long.class))
                .isZero();
    }

    @Test
    void indicatorsRespectOwnTeamAndAllScopes() {
        provisionTenant("scope");
        UUID caseX = Ids.newId();
        publishAndAwait(new InteractionRecorded(Ids.newId(), tenant, at(2026, 8, 3), Ids.newId(), caseX, Ids.newId(),
                creditorA, "PHONE", "NO_ANSWER", false, operatorX, "OUTBOUND", portfolio), "incentive.interaction-recorded");
        publishAndAwait(new InteractionRecorded(Ids.newId(), tenant, at(2026, 8, 3), Ids.newId(), caseX, Ids.newId(),
                creditorA, "PHONE", "CPC", true, operatorX, "OUTBOUND", portfolio), "incentive.interaction-recorded");
        publishAndAwait(new CallCompleted(Ids.newId(), tenant, at(2026, 8, 3), Ids.newId(), caseX, Ids.newId(), creditorA,
                operatorX, "OUTBOUND", CallCompleted.ANSWERED, 300, portfolio), "incentive.call-completed");
        publishAndAwait(new CallCompleted(Ids.newId(), tenant, at(2026, 8, 3), Ids.newId(), caseX, Ids.newId(), creditorA,
                operatorX, "OUTBOUND", CallCompleted.ANSWERED, 180, portfolio), "incentive.call-completed");
        publishAndAwait(new InteractionRecorded(Ids.newId(), tenant, at(2026, 8, 4), Ids.newId(), Ids.newId(), Ids.newId(),
                creditorA, "WHATSAPP", "CPC", true, operatorY, "INBOUND", portfolio), "incentive.interaction-recorded");
        UUID agreement = Ids.newId();
        publishAndAwait(attribution(agreement, caseX, "HUMAN", List.of(closer(operatorX)), "2000.00", 20, null,
                at(2026, 8, 3)), "incentive.attribution-recorded");
        publishAndAwait(payment(Ids.newId(), agreement, "1000.00", at(2026, 8, 6)), "incentive.payment-confirmed");

        IndicatorQuery byUser = new IndicatorQuery(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), null, null, null,
                null, null, null, "USER");
        IndicatorReport own = as(operatorX, DefaultRoles.OPERADOR, () -> performance.indicators(byUser));
        assertThat(own.rows()).extracting(IndicatorRow::groupKey).containsExactly(operatorX.toString());
        IndicatorRow x = own.rows().getFirst();
        assertThat(x.effort().attempts()).isEqualTo(2);
        assertThat(x.effort().effectiveContacts()).isEqualTo(1);
        assertThat(x.effort().effectiveContactRate()).isEqualByComparingTo("0.5000");
        assertThat(x.effort().workedCases()).isEqualTo(1);
        // TMA = (300 + 180) ÷ 2
        assertThat(x.effort().averageHandlingSeconds()).isEqualByComparingTo("240.00");
        assertThat(x.conversion().agreements()).isEqualTo(1);
        assertThat(x.conversion().contactToAgreement()).isEqualByComparingTo("1.0000");
        assertThat(x.financial().negotiatedAmount()).isEqualByComparingTo("2000.00");
        assertThat(x.financial().recoveredAmount()).isEqualByComparingTo("1000.00");
        // desconto 500 ÷ saldo atualizado 2.500; entrada 0; uso do envelope 40%
        assertThat(x.financial().averageDiscount()).isEqualByComparingTo("0.2000");
        assertThat(x.financial().envelopeUsageAverage()).isEqualByComparingTo("0.4000");
        assertThat(x.quality().safraAgreements()).isEqualTo(1);
        assertThat(own.unavailableMetrics()).contains("conversion.proposalToAgreement");

        assertThatThrownBy(() -> as(operatorY, DefaultRoles.OPERADOR, () -> performance.indicators(
                new IndicatorQuery(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), operatorX, null, null, null, null,
                        null, "NONE")))).isInstanceOf(ScopeAccessDeniedException.class);
        IndicatorReport teamView = as(supervisor, DefaultRoles.SUPERVISOR, () -> performance.indicators(byUser));
        assertThat(teamView.rows()).extracting(IndicatorRow::groupKey)
                .containsExactlyInAnyOrder(operatorX.toString(), operatorY.toString());
        IndicatorReport teams = as(manager, DefaultRoles.GESTOR, () -> performance.indicators(
                new IndicatorQuery(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), null, null, null, null, null, null,
                        "TEAM")));
        assertThat(teams.rows()).hasSize(1);
        assertThat(teams.rows().getFirst().effort().attempts()).isEqualTo(2);
        // operador Y: contato recebido (INBOUND) não é tentativa
        IndicatorReport channel = as(manager, DefaultRoles.GESTOR, () -> performance.indicators(
                new IndicatorQuery(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), operatorY, null, null, null, null,
                        null, "CHANNEL")));
        assertThat(channel.rows()).extracting(IndicatorRow::groupKey).containsExactly("WHATSAPP");
        assertThat(channel.rows().getFirst().effort().attempts()).isZero();
        assertThat(channel.rows().getFirst().effort().effectiveContacts()).isEqualTo(1);
        // escopo de credores restrito a outro credor: sem dados
        assertThat(asScoped(manager, DefaultRoles.GESTOR, java.util.Set.of(creditorB), () -> performance.indicators(byUser))
                .rows()).isEmpty();
    }

    @Test
    void monitoringGateSuspendsUntilReevaluationAndSevereViolationZeroes() {
        provisionTenant("gate");
        // plano com portão de monitoria (nota mínima 7,0): nova versão publicada a partir de setembro
        UUID seeded = as(manager, DefaultRoles.GESTOR, () -> plans.list()).getFirst().id();
        clock.set(at(2026, 8, 20));
        CommissionPlanUseCases.PlanView draft = as(manager, DefaultRoles.GESTOR, () -> plans.newVersion(seeded));
        as(manager, DefaultRoles.GESTOR, () -> plans.publish(draft.id(), LocalDate.of(2026, 9, 1)));
        clock.reset();
        UUID agreement = Ids.newId();
        publishAndAwait(attribution(agreement, Ids.newId(), "HUMAN", List.of(closer(operatorX)), "8000.00", 20, null,
                at(2026, 9, 1)), "incentive.attribution-recorded");
        publishAndAwait(payment(Ids.newId(), agreement, "4000.00", at(2026, 9, 2)), "incentive.payment-confirmed");
        UUID agreementY = Ids.newId();
        publishAndAwait(attribution(agreementY, Ids.newId(), "HUMAN", List.of(closer(operatorY)), "8000.00", 20, null,
                at(2026, 9, 1)), "incentive.attribution-recorded");
        publishAndAwait(payment(Ids.newId(), agreementY, "2000.00", at(2026, 9, 2)), "incentive.payment-confirmed");

        UUID form = as(supervisor, DefaultRoles.SUPERVISOR, () -> quality.createForm(new FormCommand("Monitoria padrão",
                null, List.of(new CriterionInput("ABORDAGEM", "Abordagem respeitosa", new BigDecimal("2"), true),
                new CriterionInput("PROCEDIMENTO", "Seguiu o roteiro", new BigDecimal("1"), false))))).id();
        // (6 × 2 + 3 × 1) ÷ 3 = 5,00 < 7,00
        assertThat(as(supervisor, DefaultRoles.SUPERVISOR, () -> quality.evaluate(new EvaluationCommand(form, operatorX,
                "CALL", Ids.newId(), LocalDate.of(2026, 9, 10), Map.of("ABORDAGEM", new BigDecimal("6"),
                "PROCEDIMENTO", new BigDecimal("3")), null))).score()).isEqualByComparingTo("5.00");
        assertThatThrownBy(() -> as(operatorX, DefaultRoles.OPERADOR, () -> quality.evaluate(new EvaluationCommand(form,
                operatorY, "CALL", null, LocalDate.of(2026, 9, 10), Map.of("ABORDAGEM", BigDecimal.TEN,
                "PROCEDIMENTO", BigDecimal.TEN), null)))).isInstanceOf(ScopeAccessDeniedException.class);
        UUID occurrence = as(supervisor, DefaultRoles.SUPERVISOR, () -> quality.registerOccurrence(new OccurrenceCommand(
                operatorY, "SEVERE_COMPLIANCE_VIOLATION", LocalDate.of(2026, 9, 15),
                "Contato fora do horário após opt-out registrado", "CASE", null))).id();

        as(manager, DefaultRoles.GESTOR, () -> periods.close(YearMonth.of(2026, 9)));
        StatementSummary x = summary(operatorX);
        assertThat(x.outcome()).isEqualTo("SUSPENDED_QUALITY_GATE");
        assertThat(x.payableAmount()).isEqualByComparingTo("0.00");
        // 4.000 × 1,5% = 60,00 suspenso
        assertThat(x.withheldAmount()).isEqualByComparingTo("60.00");
        StatementSummary y = summary(operatorY);
        assertThat(y.outcome()).isEqualTo("ZEROED_COMPLIANCE");

        // reavaliação: nova monitoria eleva a média a (5 + 10) ÷ 2 = 7,50 e o recálculo libera o variável
        clock.set(at(2026, 9, 30));
        as(supervisor, DefaultRoles.SUPERVISOR, () -> quality.evaluate(new EvaluationCommand(form, operatorX, "CALL",
                Ids.newId(), LocalDate.of(2026, 9, 29), Map.of("ABORDAGEM", BigDecimal.TEN, "PROCEDIMENTO", BigDecimal.TEN),
                "Reavaliação")));
        clock.reset();
        assertThat(as(manager, DefaultRoles.GESTOR, () -> statements.recalculate(x.id())).summary().payableAmount())
                .isEqualByComparingTo("60.00");
        // ocorrência dispensada (contestação procedente) e recálculo: 2.000 × 1,5% = 30,00
        as(manager, DefaultRoles.GESTOR, () -> quality.dismissOccurrence(occurrence,
                "Contato autorizado pelo porteiro; registro de opt-out posterior"));
        assertThat(as(manager, DefaultRoles.GESTOR, () -> statements.recalculate(y.id())).summary().payableAmount())
                .isEqualByComparingTo("30.00");

        // simulação da versão vigente com taxas dobradas sobre setembro (rascunho, sem gravar nada)
        CommissionPlanUseCases.PlanView current = as(manager, DefaultRoles.GESTOR, () -> plans.get(draft.id()));
        CommissionPlanUseCases.PlanView next = as(manager, DefaultRoles.GESTOR, () -> plans.newVersion(current.id()));
        PlanRules rules = next.rules();
        PlanRules doubled = new PlanRules(rules.eligibility(),
                new RecoveryRateComponent(TierMode.PROGRESSIVE, List.of(new RateTable(null,
                        List.of(new RateTier(BigDecimal.ZERO, new BigDecimal("3")))))),
                rules.fixedPerAgreement(), rules.goal(), rules.difficultyMultipliers(),
                new QualityModifiers(null, null, null, false), rules.attribution(), rules.teamPool(), rules.limits(),
                rules.clearing());
        as(manager, DefaultRoles.GESTOR, () -> plans.updateDraft(next.id(), new CommissionPlanUseCases.PlanCommand(
                next.code(), next.name(), next.description(), next.priority(), doubled)));
        CommissionPlanUseCases.SimulationView simulation = as(manager, DefaultRoles.GESTOR,
                () -> plans.simulate(next.id(), YearMonth.of(2026, 9)));
        // X: 4.000 × 3% = 120; Y: 2.000 × 3% = 60; supervisor: pool 6.000 × 0,3% = 18
        assertThat(simulation.simulatedTotal()).isEqualByComparingTo("198.00");
        assertThat(simulation.actualTotal()).isEqualByComparingTo("108.00");
        assertThat(count("select count(*) from incentive.commission_plan where status = 'DRAFT'")).isEqualTo(1);
        assertThatThrownBy(() -> as(manager, DefaultRoles.GESTOR, () -> plans.publish(next.id(), LocalDate.of(2026, 8, 1))))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void loggedTimeComesFromSessionsWithoutDoubleCountingConcurrentSessions() {
        provisionTenant("logged");
        system(() -> {
            jdbcTemplate.update("""
                    insert into identity.auth_session (id, tenant_id, version, created_at, updated_at, user_id, status,
                        mfa_verified, started_at, last_seen_at, absolute_expires_at)
                    values (?, ?, 0, now(), now(), ?, 'EXPIRED', false, ?::timestamptz, ?::timestamptz, ?::timestamptz)""",
                    Ids.newId(), tenant, operatorX, "2026-08-03T12:00:00Z", "2026-08-03T14:00:00Z", "2026-08-04T00:00:00Z");
            jdbcTemplate.update("""
                    insert into identity.auth_session (id, tenant_id, version, created_at, updated_at, user_id, status,
                        mfa_verified, started_at, last_seen_at, absolute_expires_at)
                    values (?, ?, 0, now(), now(), ?, 'EXPIRED', false, ?::timestamptz, ?::timestamptz, ?::timestamptz)""",
                    Ids.newId(), tenant, operatorX, "2026-08-03T13:00:00Z", "2026-08-03T15:00:00Z", "2026-08-04T00:00:00Z");
            return null;
        });
        List<WorkforceQueries.DailyLoggedTime> logged = system(() -> workforce.loggedTime(List.of(operatorX),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)));
        // 12h–15h UTC unidos (sessões sobrepostas): 3 horas no dia 03/08
        assertThat(logged).singleElement().satisfies(l -> {
            assertThat(l.date()).isEqualTo(LocalDate.of(2026, 8, 3));
            assertThat(l.seconds()).isEqualTo(3 * 3600);
        });
    }

    @Test
    void restContractIsWired() {
        assertThat(mvcConversionService.convert("2026-08", YearMonth.class)).isEqualTo(YearMonth.of(2026, 8));
        PlanRules standard = StandardPlans.padraoMercado();
        assertThat(json.fromJson(json.toJson(standard), PlanRules.class)).isEqualTo(standard);
        List<String> patterns = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream()).toList();
        assertThat(patterns).contains("/api/v1/incentive/performance", "/api/v1/incentive/plans/{id}/simulations",
                "/api/v1/incentive/periods/{period}/close", "/api/v1/incentive/statements/{id}/disputes",
                "/api/v1/incentive/statements/{id}/approve", "/api/v1/incentive/exports",
                "/api/v1/incentive/quality/evaluations", "/api/v1/incentive/ledger");
    }

    private StatementSummary summary(UUID userId) {
        return as(manager, DefaultRoles.GESTOR, () -> statements.list(YearMonth.of(2026, 9), userId, null,
                PageQuery.of(0, 5))).content().getFirst();
    }
}
