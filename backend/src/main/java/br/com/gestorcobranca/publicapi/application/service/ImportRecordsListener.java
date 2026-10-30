package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.publicapi.application.port.out.ApiImportBatchStore;
import br.com.gestorcobranca.shared.config.AsyncConfig;
import br.com.gestorcobranca.shared.events.TenantEventListener;
import br.com.gestorcobranca.shared.events.debt.PortfolioLoaded;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Atribui ao token os registros processados pelos lotes que ele enviou: a importação é assíncrona (ingestion), então
 * os títulos gravados só são conhecidos no {@code PortfolioLoaded}. Soma ao consumo da hora (rota
 * {@code ASYNC import}, sem contar requisição) e registra {@code public_api.records_processed}. Idempotente por evento.
 */
@Component
class ImportRecordsListener extends TenantEventListener {

    static final String ASYNC_METHOD = "ASYNC";
    static final String ASYNC_ROUTE = "import.records";

    private final ApiImportBatchStore batches;
    private final UsageAccumulator accumulator;
    private final BusinessEventLogger businessEvents;

    ImportRecordsListener(ApiImportBatchStore batches, UsageAccumulator accumulator, BusinessEventLogger businessEvents) {
        this.batches = batches;
        this.accumulator = accumulator;
        this.businessEvents = businessEvents;
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(PortfolioLoaded event) {
        handleOnce(event, "publicapi.import-records", () -> batches.find(event.importBatchId()).ifPresent(origin -> {
            long records = (long) event.titlesCreated() + event.titlesUpdated();
            accumulator.add(event.tenantId(), origin.creditorId(), origin.tokenId(), event.occurredAt(), ASYNC_METHOD,
                    ASYNC_ROUTE, 0, 0, 0, records, 0);
            businessEvents.log(BusinessEvent.of(BusinessEventTypes.PUBLIC_API_RECORDS_PROCESSED)
                    .creditor(origin.creditorId()).portfolio(event.portfolioId())
                    .reference("import_batch", event.importBatchId()).attr("tokenId", origin.tokenId())
                    .attr("titlesCreated", event.titlesCreated()).attr("titlesUpdated", event.titlesUpdated())
                    .attr("debtorsAffected", event.debtorsAffected()).attr("records", records));
        }));
    }
}
