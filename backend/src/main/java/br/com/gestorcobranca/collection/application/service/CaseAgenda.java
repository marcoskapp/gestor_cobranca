package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.model.CollectionTask;
import br.com.gestorcobranca.collection.domain.model.PaymentPromise;
import br.com.gestorcobranca.collection.domain.model.PromiseStatus;
import br.com.gestorcobranca.collection.domain.model.TaskStatus;
import br.com.gestorcobranca.collection.domain.repository.CollectionTaskRepository;
import br.com.gestorcobranca.collection.domain.repository.PaymentPromiseRepository;
import br.com.gestorcobranca.shared.config.TimeConfig;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;

/**
 * Próxima ação do caso ({@code nextActionAt}), que decide quando ele volta a ser oferecido:
 * <ul>
 *   <li>a tarefa em aberto mais próxima (retorno combinado ou acompanhamento; atrasada = no passado,
 *       o caso vem primeiro no "próximo caso");</li>
 *   <li>sem tarefa e com promessa pendente: o início do dia seguinte ao fim da tolerância da
 *       promessa (até lá o caso aguarda o pagamento e não é oferecido pela fila);</li>
 *   <li>nenhuma das duas: sem próxima ação (disponível conforme a ordem da fila).</li>
 * </ul>
 */
@Component
class CaseAgenda {

    private final CollectionTaskRepository tasks;
    private final PaymentPromiseRepository promises;

    CaseAgenda(CollectionTaskRepository tasks, PaymentPromiseRepository promises) {
        this.tasks = tasks;
        this.promises = promises;
    }

    void refresh(CollectionCase c) {
        if (!c.getStatus().isActive()) {
            c.scheduleNextAction(null);
            return;
        }
        Optional<Instant> task = tasks.findByCaseIdAndStatusIn(c.getId(), EnumSet.of(TaskStatus.PENDING, TaskStatus.MISSED))
                .stream().map(CollectionTask::getDueAt).min(Instant::compareTo);
        if (task.isPresent()) {
            c.scheduleNextAction(task.get());
            return;
        }
        Optional<Instant> promiseWait = promises.findByCaseIdAndStatus(c.getId(), PromiseStatus.PENDING).stream()
                .map(PaymentPromise::getBreaksAfter)
                .max(java.time.LocalDate::compareTo)
                .map(d -> d.plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant());
        c.scheduleNextAction(promiseWait.orElse(null));
    }
}
