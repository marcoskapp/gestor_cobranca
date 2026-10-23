package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.CollectionProperties;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Abertura e atualização de casos após uma carga de carteira ({@code PortfolioLoaded}): percorre
 * os devedores com títulos gravados pelo lote, um lote de devedores por execução (transação
 * própria) e enfileira a continuação. Idempotente: sincronizar um devedor sem mudança não gera
 * efeito.
 */
@Component
class PortfolioSyncJob implements JobHandler<PortfolioSyncJob.Payload> {

    static final String TYPE = "collection.portfolio_sync";

    private final DebtTitleUseCases titles;
    private final CaseSynchronizer synchronizer;
    private final JobQueue jobQueue;
    private final CollectionProperties properties;
    private final CollectionMetrics metrics;

    PortfolioSyncJob(DebtTitleUseCases titles, CaseSynchronizer synchronizer, JobQueue jobQueue,
                     CollectionProperties properties, CollectionMetrics metrics) {
        this.titles = titles;
        this.synchronizer = synchronizer;
        this.jobQueue = jobQueue;
        this.properties = properties;
        this.metrics = metrics;
    }

    record Payload(UUID creditorId, UUID portfolioId, UUID importBatchId, UUID afterDebtorId) {
    }

    void enqueue(UUID creditorId, UUID portfolioId, UUID importBatchId) {
        jobQueue.enqueue(TYPE, new Payload(creditorId, portfolioId, importBatchId, null), null,
                "batch:" + importBatchId);
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
        List<UUID> debtors = titles.debtorIdsByImportBatch(payload.creditorId(), payload.importBatchId(),
                payload.afterDebtorId(), batch);
        CaseSynchronizer.Context ctx = synchronizer.context();
        int touched = 0;
        for (UUID debtorId : debtors) {
            touched += synchronizer.syncDebtor(debtorId, payload.creditorId(), "portfolio_loaded", ctx).size();
        }
        metrics.sync("portfolio_loaded", touched, Duration.ofNanos(System.nanoTime() - started));
        if (debtors.size() == batch) {
            jobQueue.enqueue(TYPE, new Payload(payload.creditorId(), payload.portfolioId(), payload.importBatchId(),
                    debtors.getLast()), null, null);
        }
    }
}
