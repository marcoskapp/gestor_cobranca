package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.incentive.application.port.in.AssignableUserView;
import br.com.gestorcobranca.incentive.application.service.IncentiveAssignees.Purpose;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Quem pode ser escolhido nos campos de pessoa do incentivo: a lista e a gravação usam o mesmo predicado — negociador
 * ({@code CASE_WORK}) habilitado, no escopo de quem registra; na monitoria, nunca a própria pessoa.
 */
class IncentiveAssigneesTest {

    private static final UUID SELF = UUID.randomUUID();

    private final UserDirectory users = mock(UserDirectory.class);
    private final IncentiveAccess access = mock(IncentiveAccess.class);
    private final AccessLogger accessLogger = mock(AccessLogger.class);
    private final IncentiveAssignees assignees = new IncentiveAssignees(users, access, accessLogger);

    private final UserSummary self = summary(SELF, "Supervisora Sá", true, false, Set.of("CASE_WORK"));
    private final UserSummary ana = summary(UUID.randomUUID(), "Ana Araújo", true, false, Set.of("CASE_WORK"));
    private final UserSummary bruno = summary(UUID.randomUUID(), "Bruno Lima", false, true, Set.of("CASE_WORK"));
    private final UserSummary carla = summary(UUID.randomUUID(), "Carla Souza", true, false, Set.of("CASE_WORK"));
    /** Sem CASE_WORK (ex.: analista): nunca elegível. */
    private final UserSummary diego = summary(UUID.randomUUID(), "Diego Reis", true, false, Set.of("REPORT_VIEW"));
    /** Desativado (desligado): nunca elegível. */
    private final UserSummary eva = summary(UUID.randomUUID(), "Eva Nunes", false, false, Set.of("CASE_WORK"));

    private static UserSummary summary(UUID id, String name, boolean active, boolean pending, Set<String> permissions) {
        return new UserSummary(id, name, active, pending, true, Set.of(), permissions, Set.of());
    }

    @BeforeEach
    void setUp() {
        when(access.actor()).thenReturn(new Actor(Actor.ActorType.USER, SELF, UUID.randomUUID(), UUID.randomUUID(),
                "SUPERVISOR", Set.of(), CreditorScope.ALL));
        // o diretório devolve os habilitados com a permissão; o predicado confere de novo (paridade com a gravação)
        when(users.enabledUsers("CASE_WORK")).thenReturn(List.of(self, ana, bruno, carla, diego, eva));
        for (UserSummary u : List.of(self, ana, bruno, carla, diego, eva)) {
            when(users.find(u.id())).thenReturn(Optional.of(u));
        }
    }

    private List<UUID> listed(Purpose purpose) {
        return assignees.search(purpose, null, null, new PageQuery(0, 50)).content().stream()
                .map(AssignableUserView::id).toList();
    }

    private boolean saves(Purpose purpose, UUID userId) {
        try {
            assignees.requireEligible(purpose, userId, "x.not_eligible", "Não elegível");
            return true;
        } catch (BusinessRuleException e) {
            return false;
        }
    }

    @Test
    void evaluationWithTotalViewListsNegotiatorsExceptSelfAndSavingFollowsTheList() {
        when(access.visibleUsersOrNone()).thenReturn(null);
        assertThat(listed(Purpose.EVALUATION)).containsExactly(ana.id(), bruno.id(), carla.id());
        for (UserSummary u : List.of(self, ana, bruno, carla, diego, eva)) {
            assertThat(saves(Purpose.EVALUATION, u.id())).as(u.name())
                    .isEqualTo(listed(Purpose.EVALUATION).contains(u.id()));
        }
        assertThat(assignees.eligibleIds(Purpose.EVALUATION)).containsExactlyInAnyOrder(ana.id(), bruno.id(), carla.id());
    }

    @Test
    void teamScopeKeepsOnlyTeamMembers() {
        when(access.visibleUsersOrNone()).thenReturn(Set.of(SELF, ana.id(), diego.id()));
        assertThat(listed(Purpose.EVALUATION)).containsExactly(ana.id());
        assertThat(saves(Purpose.EVALUATION, carla.id())).isFalse();
        assertThat(saves(Purpose.EVALUATION, ana.id())).isTrue();
        // meta: a própria pessoa (negociadora) também pode ter meta
        assertThat(listed(Purpose.GOAL)).containsExactly(ana.id(), SELF);
        assertThat(saves(Purpose.GOAL, SELF)).isTrue();
        assertThat(saves(Purpose.GOAL, eva.id())).isFalse();
    }

    @Test
    void searchFiltersByNameWithoutAccentsResolvesIdsAndLogsWithoutTheQuery() {
        when(access.visibleUsersOrNone()).thenReturn(null);
        assertThat(assignees.search(Purpose.EVALUATION, "araujo", null, new PageQuery(0, 20)).content())
                .containsExactly(new AssignableUserView(ana.id(), "Ana Araújo"));
        assertThat(assignees.search(Purpose.EVALUATION, null, Set.of(carla.id(), eva.id()), new PageQuery(0, 20))
                .content()).extracting(AssignableUserView::id).containsExactly(carla.id());
        ArgumentCaptor<AccessEvent> event = ArgumentCaptor.forClass(AccessEvent.class);
        verify(accessLogger, org.mockito.Mockito.times(2)).log(event.capture());
        assertThat(event.getAllValues()).allSatisfy(e -> {
            assertThat(e.type()).isEqualTo(AccessEventType.USER_DIRECTORY_QUERIED);
            assertThat(e.details()).containsEntry("purpose", "EVALUATION").doesNotContainValue("araujo");
        });
        assertThatThrownBy(() -> assignees.requireEligible(Purpose.EVALUATION, null, "x.required", "Informe"))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "x.required");
    }

    @Test
    void withoutPerformanceScopeNobodyIsEligibleAndNothingIsDenied() {
        when(access.visibleUsersOrNone()).thenReturn(Set.of());
        assertThat(listed(Purpose.GOAL)).isEmpty();
        assertThat(saves(Purpose.GOAL, ana.id())).isFalse();
    }
}
