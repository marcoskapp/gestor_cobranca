package br.com.gestorcobranca.incentive;

import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases;
import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases.CriterionInput;
import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases.EvaluationCommand;
import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases.FormCommand;
import br.com.gestorcobranca.incentive.application.port.in.TierScheduling;
import br.com.gestorcobranca.incentive.application.port.in.TierUseCases;
import br.com.gestorcobranca.incentive.application.port.in.TierUseCases.AdjustCommand;
import br.com.gestorcobranca.incentive.application.port.in.TierUseCases.MyTierView;
import br.com.gestorcobranca.incentive.application.port.in.TierUseCases.PersonView;
import br.com.gestorcobranca.incentive.application.port.in.TierUseCases.RoundDetail;
import br.com.gestorcobranca.incentive.application.port.in.TierUseCases.RoundSummary;
import br.com.gestorcobranca.incentive.application.port.in.TierUseCases.SettingsCommand;
import br.com.gestorcobranca.incentive.application.port.in.TierUseCases.SettingsView;
import br.com.gestorcobranca.incentive.domain.model.tier.TierMatrix;
import br.com.gestorcobranca.pendingwork.application.port.in.PendingWorkUseCases;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.incentive.NegotiatorTierChanged;
import br.com.gestorcobranca.shared.events.scoring.ExpectedRecoveryMeasured;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Classificação de desempenho (distribuicao-por-desempenho.md §3 e §11.2): apuração com dados mínimos, matriz, portão
 * de qualidade, nível padrão, ajuste com motivo no escopo do supervisor, publicação emitindo
 * {@link NegotiatorTierChanged}, rotina (abertura, idempotência e expiração mantendo a publicação anterior) e o próprio
 * nível do operador.
 */
@Import(NegotiatorTierIT.TierEventProbe.class)
class NegotiatorTierIT extends IncentiveTestSupport {

    /** Consumidor de teste do contrato (o collection consome em outra mudança). */
    @TestComponent
    static class TierEventProbe {

        final List<NegotiatorTierChanged> received = new CopyOnWriteArrayList<>();

        @EventListener
        void on(NegotiatorTierChanged event) {
            received.add(event);
        }

        List<NegotiatorTierChanged> of(UUID publicationId) {
            return received.stream().filter(e -> e.publicationId().equals(publicationId)).toList();
        }
    }

    @Autowired
    TierUseCases tiers;
    @Autowired
    TierScheduling scheduling;
    @Autowired
    QualityUseCases quality;
    @Autowired
    PendingWorkUseCases pendingWork;
    @Autowired
    TierEventProbe probe;

    @Test
    void classifiesReviewsAndPublishes() {
        prepare("tier-flow");
        clock.set(at(2026, 11, 12));
        configure();
        UUID form = form();
        // Y: notas altas, mas uma falha crítica (nota zerada) → portão limita a qualidade a Q2
        for (int day = 1; day <= 4; day++) {
            evaluate(form, operatorY, LocalDate.of(2026, 10, day), "10", "10");
        }
        evaluate(form, operatorY, LocalDate.of(2026, 10, 5), "10", "0");
        // X: média 7,5 (acima do portão)
        evaluate(form, operatorX, LocalDate.of(2026, 10, 1), "7.5", "7.5");
        evaluate(form, operatorX, LocalDate.of(2026, 10, 2), "7.5", "7.5");
        // supervisor: uma avaliação só (abaixo do mínimo)
        as(manager, DefaultRoles.GESTOR, () -> quality.evaluate(new EvaluationCommand(form, supervisor, "OTHER", null,
                LocalDate.of(2026, 10, 3), Map.of("ABORDAGEM", BigDecimal.TEN, "CONFORMIDADE", BigDecimal.TEN), null)));
        recovery(operatorX, LocalDate.of(2026, 9, 20), 10, "1000.00", "1200.00");
        recovery(operatorY, LocalDate.of(2026, 9, 20), 10, "1000.00", "1500.00");
        // fora da janela (antes de 14/08): não conta
        recovery(operatorX, LocalDate.of(2026, 8, 1), 50, "1000.00", "9000.00");

        // operador e supervisor não apuram; o gestor apura
        assertThatThrownBy(() -> as(supervisor, DefaultRoles.SUPERVISOR, () -> tiers.assessNow()))
                .isInstanceOf(ScopeAccessDeniedException.class);
        RoundSummary created = as(manager, DefaultRoles.GESTOR, () -> tiers.assessNow());
        assertThat(created.status()).isEqualTo("PROPOSED");
        assertThat(created.origin()).isEqualTo("MANUAL");
        assertThat(created.periodFrom()).isEqualTo(LocalDate.of(2026, 8, 14));
        assertThat(created.periodTo()).isEqualTo(LocalDate.of(2026, 11, 11));
        // 3 dias úteis depois de quinta 12/11
        assertThat(created.reviewDeadline()).isEqualTo(LocalDate.of(2026, 11, 17));
        assertThatThrownBy(() -> as(manager, DefaultRoles.GESTOR, () -> tiers.assessNow()))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "tier_round.open_exists");

        // pendência para quem revisa
        assertThat(pendingWork(supervisor, DefaultRoles.SUPERVISOR)).singleElement().satisfies(i -> {
            assertThat(i.count()).isEqualTo(1);
            assertThat(i.route()).isEqualTo("/remuneracao/classificacao");
            assertThat(i.queryParams()).containsEntry("rodada", created.id().toString());
            assertThat(i.nextDueDate()).isEqualTo(LocalDate.of(2026, 11, 17));
        });
        assertThat(pendingWork(operatorX, DefaultRoles.OPERADOR)).isEmpty();

        RoundDetail full = as(manager, DefaultRoles.GESTOR, () -> tiers.round(created.id()));
        Map<UUID, PersonView> people = byUser(full.people());
        assertThat(people).containsKeys(operatorX, operatorY, supervisor, manager, manager2, admin)
                .doesNotContainKey(finance);
        // Y: qualidade Q4 limitada a Q2 pelo portão × eficiência Q4 → B (sem o portão seria A)
        assertThat(people.get(operatorY)).satisfies(p -> {
            assertThat(p.qualityQuartile()).isEqualTo(2);
            assertThat(p.efficiencyQuartile()).isEqualTo(4);
            assertThat(p.qualityGateFailed()).isTrue();
            assertThat(p.proposedTier()).isEqualTo("B");
            assertThat(p.evaluations()).isEqualTo(5);
            assertThat(p.recoveryRatio()).isEqualByComparingTo("1.5");
        });
        // X: Q2 × Q2 → C; a safra fora da janela não entrou
        assertThat(people.get(operatorX)).satisfies(p -> {
            assertThat(p.qualityQuartile()).isEqualTo(2);
            assertThat(p.efficiencyQuartile()).isEqualTo(2);
            assertThat(p.qualityGateFailed()).isFalse();
            assertThat(p.proposedTier()).isEqualTo("C");
            assertThat(p.cases()).isEqualTo(10);
        });
        // sem amostra suficiente → nível padrão (C, configurado)
        assertThat(people.get(supervisor)).satisfies(p -> {
            assertThat(p.qualityQuartile()).isNull();
            assertThat(p.efficiencyQuartile()).isNull();
            assertThat(p.proposedTier()).isEqualTo("C");
        });
        assertThat(full.canAdjust()).isTrue();
        assertThat(full.canPublish()).isTrue();
        assertThat(full.settings().defaultTier()).isEqualTo("C");

        // supervisor: só a equipe (e ele mesmo); ajusta a equipe, nunca o próprio nível nem fora do escopo; não publica
        RoundDetail team = as(supervisor, DefaultRoles.SUPERVISOR, () -> tiers.round(created.id()));
        assertThat(team.people()).extracting(PersonView::userId)
                .containsExactlyInAnyOrder(operatorX, operatorY, supervisor);
        assertThat(team.canAdjust()).isTrue();
        assertThat(team.canPublish()).isFalse();
        assertThat(as(supervisor, DefaultRoles.SUPERVISOR, () -> tiers.rounds())).singleElement()
                .satisfies(r -> assertThat(r.people()).isEqualTo(3));
        assertThatThrownBy(() -> as(supervisor, DefaultRoles.SUPERVISOR, () -> tiers.adjust(created.id(), manager,
                new AdjustCommand("A", "Troca de carteira no meio da janela")))).isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> as(supervisor, DefaultRoles.SUPERVISOR, () -> tiers.adjust(created.id(), supervisor,
                new AdjustCommand("A", "Troca de carteira no meio da janela"))))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "tier_assignment.self_adjustment");
        assertThatThrownBy(() -> as(supervisor, DefaultRoles.SUPERVISOR, () -> tiers.adjust(created.id(), operatorX,
                new AdjustCommand("B", "curto")))).hasFieldOrPropertyWithValue("code", "tier_assignment.reason_invalid");
        PersonView adjusted = as(supervisor, DefaultRoles.SUPERVISOR, () -> tiers.adjust(created.id(), operatorX,
                new AdjustCommand("B", "Férias em metade da janela de apuração")));
        assertThat(adjusted.finalTier()).isEqualTo("B");
        assertThat(adjusted.proposedTier()).isEqualTo("C");
        assertThat(adjusted.adjustedByName()).isEqualTo("Supervisor S");
        assertThatThrownBy(() -> as(supervisor, DefaultRoles.SUPERVISOR, () -> tiers.publish(created.id())))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> as(operatorX, DefaultRoles.OPERADOR, () -> tiers.round(created.id())))
                .isInstanceOf(ScopeAccessDeniedException.class);

        // antes da publicação o operador ainda não tem nível vigente
        assertThat(as(operatorX, DefaultRoles.OPERADOR, () -> tiers.mine()).status()).isEqualTo("NONE");

        RoundSummary published = as(manager, DefaultRoles.GESTOR, () -> tiers.publish(created.id()));
        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(published.validFrom()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(published.adjusted()).isEqualTo(1);
        assertThatThrownBy(() -> as(manager, DefaultRoles.GESTOR, () -> tiers.publish(created.id())))
                .isInstanceOf(ConflictException.class);
        // publicada, a rodada fica congelada: o caso de uso recusa o ajuste e o gatilho do banco também
        assertThatThrownBy(() -> as(supervisor, DefaultRoles.SUPERVISOR, () -> tiers.adjust(created.id(), operatorY,
                new AdjustCommand("A", "Ajuste depois da publicação da rodada"))))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "tier_round.not_proposed");
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("""
                update incentive.tier_assignment set final_tier = 'A', adjusted_by = ?, adjusted_at = now(),
                       adjustment_reason = 'Fora do caso de uso' where round_id = ? and user_id = ?
                """, supervisor, created.id(), operatorY)))
                .hasMessageContaining("a rodada não está mais em revisão");
        assertThat(count("select count(*) from incentive.tier_assignment where round_id = ? and user_id = ? "
                + "and final_tier = 'B' and adjusted_by is null", created.id(), operatorY)).isEqualTo(1);

        // um evento por pessoa ativa com CASE_WORK, só com o nível final
        List<NegotiatorTierChanged> events = probe.of(created.id());
        assertThat(events).hasSize(full.people().size());
        Map<UUID, NegotiatorTierChanged> byPerson = events.stream()
                .collect(Collectors.toMap(NegotiatorTierChanged::userId, Function.identity()));
        assertThat(byPerson.get(operatorX).tier()).isEqualTo("B");
        assertThat(byPerson.get(operatorY).tier()).isEqualTo("B");
        assertThat(byPerson.get(supervisor).tier()).isEqualTo("C");
        assertThat(events).allSatisfy(e -> {
            assertThat(e.tenantId()).isEqualTo(tenant);
            assertThat(e.validFrom()).isEqualTo(LocalDate.of(2026, 11, 12));
        });
        assertThat(pendingWork(supervisor, DefaultRoles.SUPERVISOR)).isEmpty();

        // o próprio nível: quartis e base, sem posição nem colegas
        MyTierView x = as(operatorX, DefaultRoles.OPERADOR, () -> tiers.mine());
        assertThat(x.status()).isEqualTo("CLASSIFIED");
        assertThat(x.tier()).isEqualTo("B");
        assertThat(x.adjusted()).isTrue();
        assertThat(x.qualityQuartile()).isEqualTo(2);
        assertThat(x.efficiencyQuartile()).isEqualTo(2);
        assertThat(x.evaluations()).isEqualTo(2);
        assertThat(x.validFrom()).isEqualTo(LocalDate.of(2026, 11, 12));
        MyTierView y = as(operatorY, DefaultRoles.OPERADOR, () -> tiers.mine());
        assertThat(y.qualityGateFailed()).isTrue();
        MyTierView s = as(supervisor, DefaultRoles.SUPERVISOR, () -> tiers.mine());
        assertThat(s.missingQuality()).isTrue();
        assertThat(s.missingEfficiency()).isTrue();
        assertThat(s.tier()).isEqualTo("C");

        // trilha: parâmetros, apuração, ajuste (com motivo) e publicação
        assertThat(count("select count(*) from shared.audit_event where action = 'tier_settings.updated'")).isEqualTo(1);
        assertThat(count("select count(*) from shared.audit_event where entity_id = ? and action in "
                + "('tier_round.proposed', 'tier_assignment.adjusted', 'tier_round.published')", created.id())).isEqualTo(3);
        assertThat(count("select count(*) from shared.audit_event where entity_id = ? and action = "
                + "'tier_assignment.adjusted' and reason = 'Férias em metade da janela de apuração'", created.id()))
                .isEqualTo(1);
    }

    @Test
    void scheduleOpensExpiresAndClearsWhoLeft() {
        prepare("tier-job");
        // rodada devida no período: sem rodada automática em novembro, a rotina de 12/11 (depois do 1º dia útil)
        // abre a do mês — uma só, mesmo rodando de novo
        clock.set(at(2026, 11, 12));
        runSchedule();
        runSchedule();
        assertThat(count("select count(*) from incentive.tier_round where origin = 'SCHEDULED' and status = 'PROPOSED'"
                + " and period_to = date '2026-11-11'")).isEqualTo(1);
        RoundSummary first = as(manager, DefaultRoles.GESTOR, () -> tiers.rounds()).getFirst();
        as(manager, DefaultRoles.GESTOR, () -> tiers.publish(first.id()));
        // sem amostra: todos no nível padrão (B)
        assertThat(probe.of(first.id())).isNotEmpty().allSatisfy(e -> assertThat(e.tier()).isEqualTo("B"));

        // mensal: o período de novembro já teve a sua rodada automática
        clock.set(at(2026, 11, 30));
        runSchedule();
        assertThat(count("select count(*) from incentive.tier_round")).isEqualTo(1);

        // 1º dia útil de dezembro: abre uma (e só uma) rodada
        clock.set(at(2026, 12, 1));
        runSchedule();
        runSchedule();
        assertThat(count("select count(*) from incentive.tier_round where origin = 'SCHEDULED' and status = 'PROPOSED'"
                + " and period_to = date '2026-11-30' and review_deadline = date '2026-12-04'")).isEqualTo(1);

        // prazo encerrado sem publicação: expira e continua valendo a publicação anterior; o período de dezembro já
        // teve a sua rodada automática, então nenhuma outra é aberta
        clock.set(at(2026, 12, 7));
        runSchedule();
        assertThat(count("select count(*) from incentive.tier_round where status = 'EXPIRED'")).isEqualTo(1);
        assertThat(count("select count(*) from incentive.tier_round where status = 'PROPOSED'")).isZero();
        MyTierView x = as(operatorX, DefaultRoles.OPERADOR, () -> tiers.mine());
        assertThat(x.validFrom()).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(count("select count(*) from shared.audit_event where action = 'tier_round.expired'")).isEqualTo(1);

        // quem foi desativado desde a última publicação volta ao padrão (nível nulo)
        system(() -> userManagement.disable(operatorY, "Desligamento"));
        RoundSummary second = as(manager, DefaultRoles.GESTOR, () -> tiers.assessNow());
        as(manager, DefaultRoles.GESTOR, () -> tiers.publish(second.id()));
        Map<UUID, NegotiatorTierChanged> byPerson = probe.of(second.id()).stream()
                .collect(Collectors.toMap(NegotiatorTierChanged::userId, Function.identity()));
        assertThat(byPerson).containsKeys(operatorX, operatorY);
        assertThat(byPerson.get(operatorY).tier()).isNull();
        assertThat(byPerson.get(operatorX).tier()).isEqualTo("B");
        assertThat(byPerson.get(operatorX).validFrom()).isEqualTo(LocalDate.of(2026, 12, 7));
    }

    @Test
    void settingsValidateTheMatrix() {
        prepare("tier-settings");
        SettingsView defaults = as(manager, DefaultRoles.GESTOR, () -> tiers.settings());
        assertThat(defaults.customized()).isFalse();
        assertThat(defaults.windowDays()).isEqualTo(90);
        assertThat(defaults.matrix()).isEqualTo(TierMatrix.standard().toMap());
        assertThatThrownBy(() -> as(supervisor, DefaultRoles.SUPERVISOR, () -> tiers.settings()))
                .isInstanceOf(ScopeAccessDeniedException.class);

        Map<String, Map<String, String>> invalid = TierMatrix.standard().toMap();
        invalid.get("Q1").put("Q1", "E");
        assertThatThrownBy(() -> as(manager, DefaultRoles.GESTOR, () -> tiers.updateSettings(new SettingsCommand(90, 5,
                30, new BigDecimal("7.0"), invalid, "B", "MONTHLY", 3))))
                .hasFieldOrPropertyWithValue("code", "tier_settings.matrix_invalid");

        Map<String, Map<String, String>> custom = TierMatrix.standard().toMap();
        custom.get("Q1").put("Q1", "C");
        SettingsView saved = as(manager, DefaultRoles.GESTOR, () -> tiers.updateSettings(new SettingsCommand(60, 3, 10,
                new BigDecimal("6.5"), custom, "A", "WEEKLY", 5)));
        assertThat(saved.customized()).isTrue();
        assertThat(as(manager, DefaultRoles.GESTOR, () -> tiers.settings())).satisfies(s -> {
            assertThat(s.windowDays()).isEqualTo(60);
            assertThat(s.periodicity()).isEqualTo("WEEKLY");
            assertThat(s.matrix().get("Q1").get("Q1")).isEqualTo("C");
            assertThat(s.qualityGate()).isEqualByComparingTo("6.5");
        });
    }

    // ------------------------------------------------------------------ cenário

    /** Assessoria com os usuários ativos (a classificação considera só quem autentica). */
    private void prepare(String prefix) {
        provisionTenant(prefix);
        system(() -> jdbcTemplate.update("update identity.app_user set status = 'ACTIVE' where status <> 'DISABLED'"));
    }

    /** Uma passada do job: expiração e abertura em transações separadas, como em {@code IncentiveJobs}. */
    private void runSchedule() {
        system(() -> {
            scheduling.expireOverdueRound();
            return null;
        });
        system(() -> {
            scheduling.openScheduledRound();
            return null;
        });
    }

    private void configure() {
        as(manager, DefaultRoles.GESTOR, () -> tiers.updateSettings(new SettingsCommand(90, 2, 5,
                new BigDecimal("7.00"), TierMatrix.standard().toMap(), "C", "MONTHLY", 3)));
    }

    private UUID form() {
        return as(supervisor, DefaultRoles.SUPERVISOR, () -> quality.createForm(new FormCommand("Monitoria", null,
                List.of(new CriterionInput("ABORDAGEM", "Abordagem respeitosa", BigDecimal.ONE, false),
                        new CriterionInput("CONFORMIDADE", "Sem ameaça ou constrangimento", BigDecimal.ONE, true)))))
                .id();
    }

    private void evaluate(UUID form, UUID user, LocalDate on, String approach, String compliance) {
        as(supervisor, DefaultRoles.SUPERVISOR, () -> quality.evaluate(new EvaluationCommand(form, user, "OTHER", null, on,
                Map.of("ABORDAGEM", new BigDecimal(approach), "CONFORMIDADE", new BigDecimal(compliance)), null)));
    }

    private void recovery(UUID user, LocalDate referenceDate, long cases, String expected, String realized) {
        publishAndAwait(new ExpectedRecoveryMeasured(Ids.newId(), tenant, at(2026, 11, 1), Ids.newId(), referenceDate, 30,
                creditorA, portfolio, user, cases, new BigDecimal("3.0"), 3, new BigDecimal(expected),
                new BigDecimal(realized), Ids.newId(), 1), "incentive.expected-recovery");
    }

    private List<PendingWorkUseCases.ItemView> pendingWork(UUID user, String role) {
        return as(user, role, () -> pendingWork.mine()).items().stream()
                .filter(i -> i.type().equals("incentive.tier_review")).toList();
    }

    private static Map<UUID, PersonView> byUser(List<PersonView> people) {
        return people.stream().collect(Collectors.toMap(PersonView::userId, Function.identity()));
    }
}
