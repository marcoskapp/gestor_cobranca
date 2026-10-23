package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.WorkforceQueries;
import br.com.gestorcobranca.identity.application.port.in.WorkforceQueries.DailyLoggedTime;
import br.com.gestorcobranca.incentive.application.port.out.FactStore;
import br.com.gestorcobranca.shared.config.TimeConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Materializa o tempo logado por dia (§2.1) a partir das sessões do identity, para quem trabalha
 * casos ({@code CASE_WORK}). Recalcula os dias informados por inteiro (idempotente).
 */
@Service
@Transactional
class LoggedTimeSync {

    private final WorkforceQueries workforce;
    private final UserDirectory directory;
    private final FactStore facts;
    private final Clock clock;

    LoggedTimeSync(WorkforceQueries workforce, UserDirectory directory, FactStore facts, Clock clock) {
        this.workforce = workforce;
        this.directory = directory;
        this.facts = facts;
        this.clock = clock;
    }

    /** Ontem e hoje (sessões que atravessam a meia-noite e o dia corrente). */
    int syncRecent() {
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        return sync(today.minusDays(1), today);
    }

    int sync(LocalDate from, LocalDate to) {
        Set<UUID> users = directory.activeUsersWithPermission("CASE_WORK").stream().map(UserDirectory.UserSummary::id)
                .collect(Collectors.toSet());
        if (users.isEmpty()) {
            return 0;
        }
        List<DailyLoggedTime> logged = workforce.loggedTime(users, from, to);
        Map<UUID, Map<LocalDate, Long>> byUser = new HashMap<>();
        logged.forEach(l -> byUser.computeIfAbsent(l.userId(), k -> new HashMap<>()).put(l.date(), l.seconds()));
        facts.replaceLoggedTime(from, to, users, byUser);
        return byUser.size();
    }
}
