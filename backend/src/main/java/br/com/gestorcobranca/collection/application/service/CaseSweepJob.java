package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.CollectionProperties;
import br.com.gestorcobranca.collection.domain.repository.CollectionCaseRepository;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reavaliação em lote de elegibilidade e fila dos casos ativos (roteamento após mudança de filas,
 * política de contato publicada, carteira ativada/suspensa). Processa um lote por execução, em
 * ordem de id, e enfileira a continuação — cada lote numa transação própria, retomável após
 * falha. Idempotente: reavaliar um caso sem mudança não gera efeito.
 */
@Component
class CaseSweepJob implements JobHandler<CaseSweepJob.Payload> {

    static final String TYPE = "collection.case_sweep";

    private final CollectionCaseRepository cases;
    private final CaseSynchronizer synchronizer;
    private final JobQueue jobQueue;
    private final CollectionProperties properties;
    private final CollectionMetrics metrics;
    private final CaseAccess access;

    CaseSweepJob(CollectionCaseRepository cases, CaseSynchronizer synchronizer, JobQueue jobQueue,
                 CollectionProperties properties, CollectionMetrics metrics, CaseAccess access) {
        this.cases = cases;
        this.synchronizer = synchronizer;
        this.jobQueue = jobQueue;
        this.properties = properties;
        this.metrics = metrics;
        this.access = access;
    }

    /**
     * @param creditorId  restringe ao credor (nulo = todos)
     * @param portfolioId restringe à carteira (tem precedência sobre o credor)
     * @param afterId     último caso do lote anterior
     * @param trigger     o que motivou (reroute, contact_policy, portfolio_status)
     */
    record Payload(UUID creditorId, UUID portfolioId, UUID afterId, String trigger) {
    }

    /**
     * Enfileira a varredura. Sem deduplicação: uma varredura em andamento pode já ter passado pelos
     * casos afetados por uma mudança posterior; repetir é seguro (sem mudança, sem efeito).
     */
    void enqueue(UUID creditorId, UUID portfolioId, String trigger, Instant runAt) {
        jobQueue.enqueue(TYPE, new Payload(creditorId, portfolioId, null, trigger), runAt, null);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<Payload> payloadType() {
        return Payload.class;
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        long started = System.nanoTime();
        int batch = properties.batchSize();
        List<UUID> ids = payload.portfolioId() != null
                ? cases.findActiveIdsByPortfolioAfter(payload.portfolioId(), payload.afterId(), batch)
                : payload.creditorId() != null
                ? cases.findActiveIdsByCreditorAfter(payload.creditorId(), payload.afterId(), batch)
                : cases.findActiveIdsAfter(payload.afterId(), batch);
        CaseSynchronizer.Context ctx = synchronizer.context();
        for (UUID id : ids) {
            cases.findDebtorIdById(id).ifPresent(access::lockDebtor);
            cases.findById(id).ifPresent(c -> synchronizer.reevaluate(c, payload.trigger(), ctx));
        }
        metrics.sync(payload.trigger(), ids.size(), Duration.ofNanos(System.nanoTime() - started));
        if (ids.size() == batch) {
            jobQueue.enqueue(TYPE, new Payload(payload.creditorId(), payload.portfolioId(), ids.getLast(),
                    payload.trigger()), null, null);
        }
    }
}
