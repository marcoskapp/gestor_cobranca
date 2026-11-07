package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.MyDayQueries;
import br.com.gestorcobranca.collection.application.port.out.MyDayStore;
import br.com.gestorcobranca.collection.application.port.out.MyDayStore.OwnCounts;
import br.com.gestorcobranca.collection.application.port.out.MyDayStore.PromiseCounts;
import br.com.gestorcobranca.collection.application.port.out.MyDayStore.TaskCounts;
import br.com.gestorcobranca.identity.application.port.in.TeamUseCases;
import br.com.gestorcobranca.identity.application.port.in.TeamUseCases.TeamView;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * "Seu dia": contagens do trabalho do usuário e das equipes que ele supervisiona, no escopo de
 * credores da sessão. Só leitura de contagens (sem dado de devedor): não gera auditoria nem log de
 * acesso a dado sensível.
 */
@Service
@Transactional(readOnly = true)
class MyDayService implements MyDayQueries {

    private final MyDayStore store;
    private final CaseAccess access;
    private final TeamUseCases teams;
    private final Clock clock;

    MyDayService(MyDayStore store, CaseAccess access, TeamUseCases teams, Clock clock) {
        this.store = store;
        this.access = access;
        this.teams = teams;
        this.clock = clock;
    }

    @Override
    public MyDayView myDay() {
        Actor actor = access.actor();
        if (!actor.isUser()) {
            throw new ScopeAccessDeniedException();
        }
        Instant now = Instant.now(clock);
        LocalDate today = CollectionViews.today(clock);
        Instant startOfToday = today.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        Instant startOfTomorrow = today.plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        Instant missedSince = startOfToday.minus(CollectionPendingWork.MISSED_WINDOW);
        Set<UUID> scope = access.creditorFilter();

        OperatorDay operator = null;
        if (actor.has(Permission.CASE_WORK)) {
            Set<UUID> me = Set.of(actor.id());
            TaskCounts tasks = store.tasks(scope, me, now, startOfTomorrow, missedSince);
            PromiseCounts promises = store.promises(scope, me, today);
            OwnCounts own = store.ownResults(scope, actor.id(), startOfToday);
            operator = new OperatorDay(store.activeCases(scope, me), tasks.today(), tasks.overdue(),
                    promises.dueToday(), promises.awaitingPayment(), own.interactions(), own.effectiveContacts(),
                    own.promises());
        }

        TeamDay team = null;
        if (actor.has(Permission.CASE_ASSIGN)) {
            List<TeamView> led = teams.list().stream()
                    .filter(t -> t.active() && actor.id().equals(t.supervisorId())).toList();
            if (!led.isEmpty()) {
                Set<UUID> teamIds = led.stream().map(TeamView::id).collect(Collectors.toSet());
                Set<UUID> members = led.stream().flatMap(t -> t.memberIds().stream()).collect(Collectors.toSet());
                TaskCounts tasks = members.isEmpty() ? new TaskCounts(0, 0)
                        : store.tasks(scope, members, now, startOfTomorrow, missedSince);
                PromiseCounts promises = members.isEmpty() ? new PromiseCounts(0, 0)
                        : store.promises(scope, members, today);
                long assigned = members.isEmpty() ? 0 : store.activeCases(scope, members);
                team = new TeamDay(led.size(), members.size(), assigned, tasks.overdue(), promises.dueToday(),
                        store.queues(scope, teamIds, now));
            }
        }
        return new MyDayView(today, operator, team);
    }
}
