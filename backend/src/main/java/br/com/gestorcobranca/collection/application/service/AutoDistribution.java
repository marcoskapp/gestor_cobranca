package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases.DistributionResult;
import br.com.gestorcobranca.collection.domain.model.CollectionQueue;
import br.com.gestorcobranca.collection.domain.repository.CollectionQueueRepository;
import br.com.gestorcobranca.compliance.application.port.in.HolidayUseCases;
import br.com.gestorcobranca.shared.config.TimeConfig;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Distribuição automática por fila (distribuicao-por-desempenho.md §4.3, job {@code collection.auto-distribution}):
 * em dias úteis (segunda a sexta, exceto feriados do calendário da assessoria no compliance), no fuso de operação,
 * cada fila ativa com distribuição automática ligada e horário vencido roda uma vez por horário por dia, com a mesma
 * regra do "Distribuir" ({@link QueueDistribution}) e o limite por rodada da fila. Chamado na transação do tenant
 * aberta pelo job (uma por fila).
 */
@Component
class AutoDistribution {

    private final CollectionQueueRepository queues;
    private final QueueDistribution distribution;
    private final HolidayUseCases holidays;
    private final Clock clock;

    AutoDistribution(CollectionQueueRepository queues, QueueDistribution distribution, HolidayUseCases holidays,
                     Clock clock) {
        this.queues = queues;
        this.distribution = distribution;
        this.holidays = holidays;
        this.clock = clock;
    }

    /** Filas com horário vencido agora, em ordem de prioridade; vazio fora dos dias úteis. */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    List<UUID> dueQueues() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        if (!isBusinessDay(now.toLocalDate())) {
            return List.of();
        }
        return queues.findByActiveTrue().stream()
                .filter(q -> q.dueAutoDistribution(now).isPresent())
                .sorted(Comparator.comparingInt(CollectionQueue::getPriority).thenComparing(CollectionQueue::getCode))
                .map(CollectionQueue::getId)
                .toList();
    }

    /**
     * Roda a distribuição automática da fila se o horário ainda estiver pendente (conferido de novo na transação: o
     * instante tratado é gravado junto com as atribuições, então o mesmo horário não roda duas vezes). A fila é lida
     * com bloqueio pessimista até o fim da transação, como no "Distribuir" manual.
     *
     * @return resultado, ou vazio se não havia horário pendente
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<DistributionResult> run(UUID queueId) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        // bloqueio da fila: serializa com o "Distribuir" manual e com outra instância (cargas e cursor consistentes)
        Optional<CollectionQueue> queue = queues.findByIdForUpdate(queueId);
        if (queue.isEmpty() || queue.get().dueAutoDistribution(now).isEmpty() || !isBusinessDay(now.toLocalDate())) {
            return Optional.empty();
        }
        CollectionQueue q = queue.get();
        q.autoDistributionHandled(now.toInstant());
        return Optional.of(distribution.run(q, q.autoDistribution().limit(), QueueDistribution.Trigger.AUTOMATIC));
    }

    private boolean isBusinessDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && holidays.list(date, date).isEmpty();
    }
}
