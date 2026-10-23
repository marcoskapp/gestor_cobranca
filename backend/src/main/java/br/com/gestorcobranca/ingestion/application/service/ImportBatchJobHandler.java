package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.jobs.PermanentJobFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Job de processamento de lote: cada execução é uma fatia ({@link ImportBatchProcessor}); se
 * ainda houver linhas, enfileira a fatia seguinte na mesma transação que conclui esta. Falhas
 * transitórias seguem o backoff da fila e retomam do checkpoint; esgotadas as tentativas, o lote é
 * encerrado como falho (sem ficar preso em processamento).
 */
@Component
class ImportBatchJobHandler implements JobHandler<ImportBatchJobHandler.Payload> {

    static final String TYPE = "ingestion.process-batch";
    private static final Logger LOG = LoggerFactory.getLogger(ImportBatchJobHandler.class);

    private final ImportBatchProcessor processor;
    private final JobQueue jobQueue;

    ImportBatchJobHandler(ImportBatchProcessor processor, JobQueue jobQueue) {
        this.processor = processor;
        this.jobQueue = jobQueue;
    }

    /** @param slice número da fatia (0 = primeira); compõe a chave de deduplicação */
    record Payload(UUID batchId, int slice) {
    }

    static String deduplicationKey(UUID batchId, int slice) {
        return "batch:" + batchId + ":" + slice;
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
        ImportBatchProcessor.SliceOutcome outcome;
        try {
            outcome = processor.runSlice(payload.batchId());
        } catch (PermanentJobFailure e) {
            throw e;
        } catch (RuntimeException e) {
            if (context.attempt() < maxAttempts()) {
                throw e;
            }
            LOG.atError().addKeyValue("batchId", payload.batchId()).addKeyValue("attempt", context.attempt())
                    .setCause(e).log("Processamento do lote falhou em todas as tentativas; lote encerrado como falho");
            processor.fail(payload.batchId(), "import.processing_failed",
                    "Falha técnica persistente no processamento; reprocesse o lote");
            return;
        }
        if (outcome == ImportBatchProcessor.SliceOutcome.CONTINUE) {
            int next = payload.slice() + 1;
            jobQueue.enqueue(TYPE, new Payload(payload.batchId(), next), null, deduplicationKey(payload.batchId(), next));
        }
    }
}
