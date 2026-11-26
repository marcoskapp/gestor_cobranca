package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Job de uso único (SEG-20261124-D01h): o lote bloqueado durante o processamento acabou de encerrar; conclui as
 * remoções de arquivo pendentes da assessoria sem esperar o job diário {@code ingestion.file-retention}, que continua
 * como última rede (queda da instância, falha esgotada). Idempotente: pendência já concluída não aparece de novo.
 */
@Component
class PendingErasureJobHandler implements JobHandler<PendingErasureJobHandler.Payload> {

    static final String TYPE = "ingestion.pending-erasure";

    private final ImportFileRetention retention;

    PendingErasureJobHandler(ImportFileRetention retention) {
        this.retention = retention;
    }

    /** @param batchId lote que encerrou (só para a deduplicação e o diagnóstico) */
    record Payload(UUID batchId) {
    }

    static String deduplicationKey(UUID batchId) {
        return "pending-erasure:" + batchId;
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
    public int maxAttempts() {
        return 3;
    }

    /** Cada rodada abre a própria transação (como o job diário). */
    @Override
    public boolean transactional() {
        return false;
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        // a falha sobe: a fila tenta de novo (até maxAttempts); o job diário fica como última rede
        retention.completePendingErasuresOrThrow(context.tenantId());
    }
}
