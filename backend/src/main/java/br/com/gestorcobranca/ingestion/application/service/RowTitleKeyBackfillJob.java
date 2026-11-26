package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.ingestion.application.port.out.ImportFileRetentionStore;
import br.com.gestorcobranca.ingestion.application.port.out.ImportFileRetentionStore.KeylessRow;
import br.com.gestorcobranca.shared.crypto.ExternalIdDigest;
import br.com.gestorcobranca.shared.crypto.ExternalIdDigest.Kind;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Preenchimento de uso único (decisão 1476): a chave HMAC do título nas linhas gravadas antes da V2309, para a detecção
 * nos arquivos achar também essas linhas depois que o identificador do título vira {@code anon:}. A V2309 enfileira um
 * job por assessoria; cada execução preenche um lote e reenfileira a continuação. Idempotente: só as linhas sem chave
 * (o gatilho da linha aceita só esse preenchimento). Log só com quantidades.
 */
@Component
class RowTitleKeyBackfillJob implements JobHandler<RowTitleKeyBackfillJob.Payload> {

    static final String TYPE = "ingestion.row-title-key-backfill";
    static final int BATCH = 1000;
    private static final Logger LOG = LoggerFactory.getLogger(RowTitleKeyBackfillJob.class);

    private final ImportFileRetentionStore store;
    private final ExternalIdDigest digest;
    private final JobQueue jobQueue;

    RowTitleKeyBackfillJob(ImportFileRetentionStore store, ExternalIdDigest digest, JobQueue jobQueue) {
        this.store = store;
        this.digest = digest;
        this.jobQueue = jobQueue;
    }

    /** @param afterId última linha tratada (nula no início) */
    record Payload(java.util.UUID afterId) {
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
        List<KeylessRow> rows = store.rowsWithoutTitleKey(payload == null ? null : payload.afterId(), BATCH);
        Map<java.util.UUID, String> keys = new LinkedHashMap<>();
        for (KeylessRow r : rows) {
            // sem forma canônica (SEG-20261126-X11): a linha fica sem chave e o cursor segue
            String key = digest.key(context.tenantId(), Kind.TITLE, r.creditorId(), r.externalId());
            if (key != null) {
                keys.put(r.id(), key);
            }
        }
        int assigned = store.assignTitleKeys(keys);
        LOG.atInfo().addKeyValue("rows", rows.size()).addKeyValue("assigned", assigned)
                .log("Lote de chaves de título das linhas da carga preenchido");
        if (rows.size() == BATCH) {
            java.util.UUID last = rows.getLast().id();
            jobQueue.enqueue(TYPE, new Payload(last), null, TYPE + ":" + last);
        }
    }
}
