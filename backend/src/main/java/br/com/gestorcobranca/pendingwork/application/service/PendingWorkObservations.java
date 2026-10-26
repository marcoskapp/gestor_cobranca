package br.com.gestorcobranca.pendingwork.application.service;

import br.com.gestorcobranca.pendingwork.application.port.in.PendingWorkUseCases.ItemView;
import br.com.gestorcobranca.pendingwork.application.port.out.PendingWorkObservationStore;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Log de negócio do envelhecimento das pendências: na primeira consulta do dia em que um tipo
 * aparece para o usuário, registra {@code pending_work.observed} com contagem, vencidos, idade do
 * item mais antigo e faixa de idade. A unicidade por usuário, tipo e dia é garantida no banco
 * ({@code pendingwork.daily_observation}); o conjunto em memória só evita repetir a tentativa na
 * mesma instância. Falha aqui nunca derruba a resposta.
 */
@Component
class PendingWorkObservations {

    private static final Logger LOG = LoggerFactory.getLogger(PendingWorkObservations.class);

    private final PendingWorkObservationStore store;
    private final BusinessEventLogger businessEvents;
    private final PendingWorkMetrics metrics;
    private final TransactionTemplate transaction;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private volatile LocalDate seenDay;

    PendingWorkObservations(PendingWorkObservationStore store, BusinessEventLogger businessEvents,
                            PendingWorkMetrics metrics, PlatformTransactionManager transactionManager) {
        this.store = store;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    void observe(Actor actor, LocalDate day, Instant now, List<ItemView> items) {
        if (!day.equals(seenDay)) {
            seen.clear();
            seenDay = day;
        }
        for (ItemView item : items) {
            String key = actor.tenantId() + ":" + actor.id() + ":" + item.type();
            if (!seen.add(key)) {
                continue;
            }
            try {
                boolean first = Boolean.TRUE.equals(transaction.execute(status -> {
                    boolean recorded = store.recordFirst(actor.id(), day, item.type(), item.count(), item.overdue(), now);
                    if (recorded) {
                        businessEvents.log(event(item, now));
                    }
                    return recorded;
                }));
                metrics.observation(first ? "recorded" : "already_recorded");
            } catch (RuntimeException e) {
                seen.remove(key);
                metrics.observation("failed");
                LOG.atWarn().addKeyValue("itemType", item.type()).setCause(e)
                        .log("Falha ao registrar observação diária de pendências");
            }
        }
    }

    static BusinessEvent event(ItemView item, Instant now) {
        Long ageHours = item.oldestAt() == null ? null : Math.max(0, Duration.between(item.oldestAt(), now).toHours());
        return BusinessEvent.of(BusinessEventTypes.PENDING_WORK_OBSERVED)
                .attr("itemType", item.type())
                .attr("source", item.source())
                .attr("total", item.count())
                .attr("overdue", item.overdue())
                .attr("dueToday", item.dueToday())
                .attr("severity", item.severity())
                .attr("oldestAgeHours", ageHours)
                .attr("ageBand", ageBand(ageHours))
                .attr("nextDueDate", item.nextDueDate() == null ? null : item.nextDueDate().toString());
    }

    /** Faixas de idade do item mais antigo: {@code <1d}, {@code 1-3d}, {@code 3-7d}, {@code 7-30d}, {@code 30d+}. */
    static String ageBand(Long hours) {
        if (hours == null) {
            return null;
        }
        if (hours < 24) {
            return "<1d";
        }
        if (hours < 72) {
            return "1-3d";
        }
        if (hours < 168) {
            return "3-7d";
        }
        return hours < 720 ? "7-30d" : "30d+";
    }
}
