package br.com.gestorcobranca.collection;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases.AssignmentView;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases.DistributionPreview;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases.DistributionResult;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases.MemberPreview;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases.TierBandCount;
import br.com.gestorcobranca.collection.application.port.in.CollectionSettingsUseCases.TierSettingsCommand;
import br.com.gestorcobranca.collection.application.port.in.QueueUseCases.AutoDistributionInput;
import br.com.gestorcobranca.collection.application.port.in.QueueUseCases.QueueCommand;
import br.com.gestorcobranca.collection.application.port.in.QueueUseCases.QueueView;
import br.com.gestorcobranca.collection.application.service.CollectionTestHooks;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.incentive.NegotiatorTierChanged;
import br.com.gestorcobranca.shared.events.scoring.CaseScoreChanged;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Distribuição priorizada por desempenho do negociador, fase 1 (distribuicao-por-desempenho.md §4 e §11.3): projeção
 * do {@code NegotiatorTierChanged}, estratégia {@code TIERED} no "Distribuir" (com a prévia) e no "Próximo caso",
 * distribuição automática por horário e o motivo (nível e faixa) no histórico de atribuição.
 * <p>
 * Cenário: X é nível A e Y nível D (equipe da fila); fila por nível com capacidade 10 (A = 12, D = 8). Sem score, o
 * valor esperado é o saldo.
 */
class TieredDistributionIT extends CollectionTestSupport {

    static final String[] CPFS = {"543.231.948-97", "757.491.186-06", "252.760.189-87", "555.979.711-15",
            "471.049.746-01", "507.529.170-80", "342.366.712-55", "768.426.846-50", "563.212.233-60"};

    private final Map<UUID, CaseView> views = new HashMap<>();

    @BeforeEach
    void setUp() {
        provision("tiered");
    }

    @AfterEach
    void resetRoll() {
        CollectionTestHooks.developmentRoll(applicationContext, null);
    }

    @Test
    void projectionIsIdempotentByPublicationAndFollowsValidity() {
        UUID publication = Ids.newId();
        publishTier(operatorX, "A", LocalDate.now(ZONE), publication);
        // reentrega da mesma publicação com outro eventId não grava de novo
        publishTier(operatorX, "A", LocalDate.now(ZONE), publication);
        assertThat(count("select count(*) from collection.negotiator_tier where user_id = ?", operatorX)).isEqualTo(1);
        // nível com vigência futura ainda não vale; "sem nível" volta ao padrão (B)
        publishTier(operatorY, "D", LocalDate.now(ZONE), Ids.newId());
        publishTier(operatorY, "A", LocalDate.now(ZONE).plusDays(30), Ids.newId());
        publishTier(operatorX, null, LocalDate.now(ZONE), Ids.newId());
        QueueView queue = tieredQueue();
        loadCases("300.00", "200.00", "100.00");
        DistributionPreview preview = asSupervisor(() -> work.previewDistribution(queue.id(), 100));
        assertThat(preview.members()).filteredOn(m -> m.userId().equals(operatorY)).extracting(MemberPreview::tier)
                .containsExactly("D");
        assertThat(preview.members()).filteredOn(m -> m.userId().equals(operatorX)).extracting(MemberPreview::tier)
                .containsExactly("B");
        // inválido (fora de A–D) não trava o consumo nem grava
        publishTier(operatorY, "E", LocalDate.now(ZONE), Ids.newId());
        assertThat(count("select count(*) from collection.negotiator_tier where user_id = ?", operatorY)).isEqualTo(2);
    }

    @Test
    void previewThenDistributeByTierRecordsTierAndBand() {
        publishTiers();
        withoutDevelopment();
        QueueView queue = tieredQueue();
        // ordem de carga embaralhada (o id não decide); com score: 600 (p 1,0) → 600; 300 (p 0,1) → 30; sem score,
        // probabilidade média 0,55: 500 → 275, 400 → 220, 200 → 110, 100 → 55
        Map<String, UUID> cases = loadCases("300.00", "100.00", "400.00", "600.00", "200.00", "500.00");
        score(cases.get("600.00"), 1000);
        score(cases.get("300.00"), 100);

        DistributionPreview preview = asSupervisor(() -> work.previewDistribution(queue.id(), 100));
        assertThat(preview.strategy()).isEqualTo("TIERED");
        assertThat(preview.considered()).isEqualTo(6);
        assertThat(preview.assigned()).isEqualTo(6);
        // altos → A; médios sem B nem C sobem ao A; baixos sem C descem ao D
        assertThat(preview.byTierBand()).containsExactlyInAnyOrder(new TierBandCount("A", "HIGH", 2),
                new TierBandCount("A", "MEDIUM", 2), new TierBandCount("D", "LOW", 2));
        assertThat(preview.members()).extracting(MemberPreview::userId, MemberPreview::tier, MemberPreview::capacity,
                MemberPreview::planned).containsExactly(org.assertj.core.groups.Tuple.tuple(operatorX, "A", 12, 4),
                org.assertj.core.groups.Tuple.tuple(operatorY, "D", 8, 2));
        // sem visão de desempenho: só quantidades (sem nível nem capacidade efetiva por pessoa)
        DistributionPreview blind = asAssignerWithoutPerformanceView(() -> work.previewDistribution(queue.id(), 100));
        assertThat(blind.members()).allSatisfy(m -> {
            assertThat(m.tier()).isNull();
            assertThat(m.capacity()).isNull();
        });
        // ... e sem quantidades por nível: só os totais por faixa (nível nulo)
        assertThat(blind.byTier()).isEmpty();
        assertThat(blind.byTierBand()).containsExactlyInAnyOrder(new TierBandCount(null, "HIGH", 2),
                new TierBandCount(null, "MEDIUM", 2), new TierBandCount(null, "LOW", 2));
        // vê o nível de parte dos membros (o próprio): o próprio nível aparece, as quantidades por nível não
        DistributionPreview partial = asAssignerSeeingOwnTier(operatorX,
                () -> work.previewDistribution(queue.id(), 100));
        assertThat(partial.members()).filteredOn(m -> m.userId().equals(operatorX)).extracting(MemberPreview::tier)
                .containsExactly("A");
        assertThat(partial.members()).filteredOn(m -> m.userId().equals(operatorY)).extracting(MemberPreview::tier)
                .containsOnlyNulls();
        assertThat(partial.byTier()).isEmpty();
        assertThat(partial.byTierBand()).extracting(TierBandCount::tier).containsOnlyNulls();
        assertThat(preview.byTier()).isNotEmpty();
        // a prévia não grava nem atribui
        assertThat(count("select count(*) from collection.case_assignment where mode = 'DISTRIBUTION'")).isZero();
        assertThat(count("select count(*) from collection.collection_case where assignee_user_id is not null")).isZero();

        DistributionResult result = asSupervisor(() -> work.distribute(queue.id(), 100));
        assertThat(result.assigned()).isEqualTo(6);
        assertThat(assignmentOf(cases.get("600.00"))).containsExactly(operatorX.toString(), "A", "HIGH");
        assertThat(assignmentOf(cases.get("400.00"))).containsExactly(operatorX.toString(), "A", "MEDIUM");
        assertThat(assignmentOf(cases.get("100.00"))).containsExactly(operatorY.toString(), "D", "LOW");
        assertThat(assignmentOf(cases.get("300.00"))).as("score baixo derruba o valor esperado")
                .containsExactly(operatorY.toString(), "D", "LOW");
        assertThat(assignmentOf(cases.get("500.00"))).containsExactly(operatorX.toString(), "A", "HIGH");
        assertThat(count("select count(*) from shared.business_event where event_type = 'queue.distributed' "
                + "and attributes ->> 'trigger' = 'MANUAL' and attributes ->> 'bandHIGH' = '2'")).isEqualTo(1);

        // histórico: o supervisor da equipe vê o nível; outro operador só a faixa
        UUID caseId = cases.get("600.00");
        AssignmentView seen = asSupervisor(() -> work.assignmentHistory(caseId, PageQuery.of(0, 10))).content().getFirst();
        assertThat(seen.tier()).isEqualTo("A");
        assertThat(seen.potentialBand()).isEqualTo("HIGH");
        AssignmentView hidden = asOperator(operatorY, () -> work.assignmentHistory(caseId, PageQuery.of(0, 10)))
                .content().getFirst();
        assertThat(hidden.tier()).isNull();
        assertThat(hidden.potentialBand()).isEqualTo("HIGH");
        assertThat(asOperator(operatorX, () -> work.assignmentHistory(caseId, PageQuery.of(0, 10))).content()
                .getFirst().tier()).as("o próprio nível").isEqualTo("A");
    }

    @Test
    void nextCaseFollowsTheOperatorTierAndDevelopmentTurn() {
        publishTiers();
        withoutDevelopment();
        QueueView queue = tieredQueue();
        Map<String, UUID> cases = loadCases("50.00", "900.00", "450.00");

        // D pega a faixa baixa primeiro; A, a alta
        CaseView low = asOperator(operatorY, () -> work.nextCase(queue.id())).orElseThrow();
        assertThat(low.id()).isEqualTo(cases.get("50.00"));
        assertThat(assignmentOf(low.id())).containsExactly(operatorY.toString(), "D", "LOW");
        CaseView high = asOperator(operatorX, () -> work.nextCase(queue.id())).orElseThrow();
        assertThat(high.id()).isEqualTo(cases.get("900.00"));
        assertThat(assignmentOf(high.id())).containsExactly(operatorX.toString(), "A", "HIGH");

        // vez de desenvolvimento: o D recebe o melhor alto disponível (faixa DEVELOPMENT no histórico)
        Map<String, UUID> more = loadCases("20.00", "800.00");
        asManager(() -> settings.updateTierSettings(new TierSettingsCommand(capacity(), 50, "B")));
        CollectionTestHooks.developmentRoll(applicationContext, 0);
        CaseView development = asOperator(operatorY, () -> work.nextCase(queue.id())).orElseThrow();
        assertThat(development.id()).isEqualTo(more.get("800.00"));
        assertThat(assignmentOf(development.id())).containsExactly(operatorY.toString(), "D", "DEVELOPMENT");
        // DEVELOPMENT no "próximo caso" só acontece para C/D: quem não vê o nível de Y vê HIGH (e sem nível)
        AssignmentView masked = asOperator(operatorX, () -> work.assignmentHistory(development.id(),
                PageQuery.of(0, 10))).content().getFirst();
        assertThat(masked.tier()).isNull();
        assertThat(masked.potentialBand()).isEqualTo("HIGH");
        AssignmentView revealed = asSupervisor(() -> work.assignmentHistory(development.id(), PageQuery.of(0, 10)))
                .content().getFirst();
        assertThat(revealed.tier()).isEqualTo("D");
        assertThat(revealed.potentialBand()).isEqualTo("DEVELOPMENT");
        // o log de negócio também leva HIGH (o nível não vai ao log)
        assertThat(count("select count(*) from shared.business_event where event_type = 'case.assigned' "
                + "and attributes ->> 'potentialBand' = 'DEVELOPMENT'")).isZero();
        assertThat(count("select count(*) from shared.business_event where event_type = 'case.assigned' "
                + "and case_id = ? and attributes ->> 'potentialBand' = 'HIGH'", development.id())).isEqualTo(1);
    }

    @Test
    void autoDistributionRunsOncePerSlotOnBusinessDays() {
        publishTiers();
        withoutDevelopment();
        QueueView queue = tieredQueue();
        assertThatThrownBy(() -> asManager(() -> queues.create(new QueueCommand("AUTO_PULL", "Auto sob demanda", null, 5,
                creditorB, null, null, null, null, null, null, null, "OLDEST_OVERDUE", "PULL", null, Set.of(), null, null,
                null, new AutoDistributionInput(true, List.of("09:00"), 50)))))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("queue.auto_distribution_pull");
        QueueView auto = asManager(() -> queues.update(queue.id(), tieredCommand(new AutoDistributionInput(true,
                List.of("09:00"), 2))));
        assertThat(auto.autoDistribution().enabled()).isTrue();
        assertThat(auto.autoDistribution().times()).containsExactly("09:00");
        loadCases("300.00", "200.00", "100.00");

        // próxima quarta-feira às 10h (dia útil, depois do horário): uma rodada, limitada a 2 casos
        LocalDate wednesday = nextWeekday(DayOfWeek.WEDNESDAY);
        moveClockTo(wednesday, 10);
        assertThat(CollectionTestHooks.autoDistribute(applicationContext, tenant)).isEqualTo(1);
        assertThat(count("select count(*) from collection.collection_case where assignee_user_id is not null"))
                .isEqualTo(2);
        assertThat(count("select count(*) from collection.case_assignment where mode = 'DISTRIBUTION' "
                + "and tier is not null and potential_band is not null")).isEqualTo(2);
        assertThat(count("select count(*) from shared.audit_event where action = 'collection_queue.distributed' "
                + "and metadata ->> 'trigger' = 'AUTOMATIC'")).isEqualTo(1);
        // o mesmo horário não roda de novo no dia
        assertThat(CollectionTestHooks.autoDistribute(applicationContext, tenant)).isZero();

        // sábado seguinte (horário pendente desde quarta): não é dia útil, não roda
        clock.reset();
        moveClockTo(wednesday.plusDays(3), 10);
        assertThat(CollectionTestHooks.autoDistribute(applicationContext, tenant)).isZero();
        assertThat(count("select count(*) from collection.collection_case where assignee_user_id is not null"))
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------ apoio

    private void publishTiers() {
        publishTier(operatorX, "A", LocalDate.now(ZONE), Ids.newId());
        publishTier(operatorY, "D", LocalDate.now(ZONE), Ids.newId());
    }

    private void publishTier(UUID userId, String tier, LocalDate validFrom, UUID publication) {
        publishAndAwait(new NegotiatorTierChanged(Ids.newId(), tenant, Instant.now(), userId, tier, validFrom,
                publication), "collection.negotiator-tier");
    }

    private static Map<String, Integer> capacity() {
        return Map.of("A", 120, "B", 110, "C", 100, "D", 80);
    }

    private void withoutDevelopment() {
        asManager(() -> settings.updateTierSettings(new TierSettingsCommand(capacity(), 0, "B")));
    }

    private QueueView tieredQueue() {
        QueueView queue = asManager(() -> queues.create(tieredCommand(null)));
        drainJobs();
        return queue;
    }

    private QueueCommand tieredCommand(AutoDistributionInput auto) {
        return new QueueCommand("POR_NIVEL", "Por nível", null, 10, creditorA, null, null, null, null, null, null, null,
                "OLDEST_OVERDUE", "TIERED", 10, Set.of(team), null, null, null, auto);
    }

    /** Um caso por saldo, cada um de um devedor; devolve o id do caso por saldo. */
    private Map<String, UUID> loadCases(String... principals) {
        Map<String, UUID> ids = new HashMap<>();
        int offset = (int) count("select count(*) from collection.collection_case");
        for (int i = 0; i < principals.length; i++) {
            UUID debtor = debtor(CPFS[offset + i]);
            load(creditorA, portfolioA, debtor, principals[i]);
            CaseView c = activeCase(debtor, portfolioA);
            ids.put(principals[i], c.id());
            views.put(c.id(), c);
        }
        awaitIdle("CollectionEventListeners");
        return ids;
    }

    private void score(UUID caseId, int score) {
        CaseView c = views.get(caseId);
        Instant at = Instant.now();
        publishAndAwait(new CaseScoreChanged(Ids.newId(), tenant, at, c.id(), c.debtorId(), c.creditorId(),
                c.portfolioId(), score, "C", null, null, Ids.newId(), 1, "SCORECARD", 30, at), "collection.case-score");
    }

    /** Período vigente do caso: operador, nível e faixa. */
    private List<String> assignmentOf(UUID caseId) {
        return system(() -> jdbcTemplate.queryForObject("""
                select user_id::text, tier, potential_band from collection.case_assignment
                 where case_id = ? and valid_to is null
                """, (rs, i) -> java.util.Arrays.asList(rs.getString(1), rs.getString(2), rs.getString(3)), caseId));
    }

    private <T> T asAssignerWithoutPerformanceView(Supplier<T> action) {
        return tenantExecution.callAs(new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(), "distribuidor",
                EnumSet.of(Permission.CASE_VIEW, Permission.CASE_ASSIGN), CreditorScope.ALL), action);
    }

    /** Distribui (todos os credores) e vê só o próprio nível ({@code PERFORMANCE_VIEW_OWN}). */
    private <T> T asAssignerSeeingOwnTier(UUID userId, Supplier<T> action) {
        return tenantExecution.callAs(new Actor(Actor.ActorType.USER, userId, tenant, Ids.newId(), "distribuidor",
                EnumSet.of(Permission.CASE_VIEW, Permission.CASE_ASSIGN, Permission.PERFORMANCE_VIEW_OWN),
                CreditorScope.ALL), action);
    }

    private static LocalDate nextWeekday(DayOfWeek day) {
        LocalDate date = LocalDate.now(ZONE).plusDays(1);
        while (date.getDayOfWeek() != day) {
            date = date.plusDays(1);
        }
        return date;
    }

    private void moveClockTo(LocalDate date, int hour) {
        Instant target = ZonedDateTime.of(date.atTime(hour, 0), ZONE).toInstant();
        clock.advance(Duration.between(Instant.now(), target));
    }
}
