package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.domain.model.ErasedSubjectReturn;
import br.com.gestorcobranca.compliance.domain.repository.ErasedSubjectReturnRepository;
import br.com.gestorcobranca.debt.application.port.spi.ErasedSubjects.ReturnAttempt;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Grava a tentativa de retorno do titular eliminado (decisão 1471) numa transação própria: a operação que a trouxe
 * (cadastro, linha da carga) é desfeita pela recusa, a tentativa fica para revisão. Métrica e evento sem dado pessoal
 * (sem o pedido, que identificaria o titular a quem lê o credor no log).
 */
@Component
class ErasedSubjectReturnRecorder {

    static final String METRIC = "compliance.erased_subject.returns_refused";

    private final ErasedSubjectReturnRepository returns;
    private final BusinessEventLogger businessEvents;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    ErasedSubjectReturnRecorder(ErasedSubjectReturnRepository returns, BusinessEventLogger businessEvents,
                                MeterRegistry meterRegistry, Clock clock) {
        this.returns = returns;
        this.businessEvents = businessEvents;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Map<UUID, Integer> linesByRequest, ReturnAttempt attempt) {
        Instant now = Instant.now(clock);
        ErasedSubjectReturn.Source source = ErasedSubjectReturn.Source.valueOf(attempt.source().name());
        List<ErasedSubjectReturn> rows = linesByRequest.entrySet().stream()
                .map(e -> new ErasedSubjectReturn(e.getKey(), attempt.creditorId(), source, attempt.batchId(),
                        e.getValue(), now))
                .toList();
        returns.saveAll(rows);
        int lines = linesByRequest.values().stream().mapToInt(Integer::intValue).sum();
        BusinessEvent event = BusinessEvent.of(BusinessEventTypes.ERASED_SUBJECT_RETURN_REFUSED)
                .attr("source", source.name())
                .attr("subjects", rows.size())
                .attr("lines", lines);
        if (attempt.creditorId() != null) {
            event.creditor(attempt.creditorId());
        }
        if (attempt.batchId() != null) {
            event.reference("import_batch", attempt.batchId());
        }
        // o evento de negócio já sai depois do commit; a métrica (e o alarme) também, para não contar o que não ficou
        businessEvents.log(event);
        String tag = source.name().toLowerCase(Locale.ROOT);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                meterRegistry.counter(METRIC, "source", tag).increment(rows.size());
            }
        });
    }
}
