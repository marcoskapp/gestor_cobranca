package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.domain.repository.DebtorRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Preenchimento de uso único (decisão 1476): os titulares anonimizados antes da troca do identificador têm os
 * identificadores de título e de contrato com CPF/CNPJ trocados por {@code anon:} + HMAC, como na anonimização. A V0316
 * enfileira um job por assessoria; cada execução trata um lote de devedores (cursor por id) e reenfileira a
 * continuação. Idempotente: o que já foi trocado não muda. Nunca por HTTP. Auditoria e log só com quantidades.
 */
@Component
class ExternalIdAnonymizationJob implements JobHandler<ExternalIdAnonymizationJob.Payload> {

    static final String TYPE = "debt.external-id-anonymization";
    static final int BATCH = 200;
    private static final Logger LOG = LoggerFactory.getLogger(ExternalIdAnonymizationJob.class);

    private final DebtorRepository debtors;
    private final ExternalIdAnonymizer anonymizer;
    private final JobQueue jobQueue;
    private final AuditTrail auditTrail;

    ExternalIdAnonymizationJob(DebtorRepository debtors, ExternalIdAnonymizer anonymizer, JobQueue jobQueue,
                               AuditTrail auditTrail) {
        this.debtors = debtors;
        this.anonymizer = anonymizer;
        this.jobQueue = jobQueue;
        this.auditTrail = auditTrail;
    }

    /** @param afterId último devedor tratado (nulo no início) */
    record Payload(UUID afterId) {
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
        List<UUID> ids = debtors.findAnonymizedIdsAfter(payload == null ? null : payload.afterId(), BATCH);
        int titles = 0;
        int contracts = 0;
        for (UUID debtorId : ids) {
            ExternalIdAnonymizer.Result r = anonymizer.anonymize(context.tenantId(), debtorId,
                    ExternalIdAnonymizer.ORIGIN_BACKFILL);
            if (!r.isEmpty()) {
                auditTrail.record(AuditEntry.of("debtor.external_ids_anonymized", "debtor", debtorId)
                        .meta("origin", ExternalIdAnonymizer.ORIGIN_BACKFILL)
                        .meta("titleIdsAnonymized", r.titles())
                        .meta("contractIdsAnonymized", r.contracts()));
            }
            titles += r.titles();
            contracts += r.contracts();
        }
        LOG.atInfo().addKeyValue("debtors", ids.size()).addKeyValue("titles", titles).addKeyValue("contracts", contracts)
                .log("Lote de troca de identificadores de titulares anonimizados processado");
        if (ids.size() == BATCH) {
            UUID last = ids.getLast();
            jobQueue.enqueue(TYPE, new Payload(last), null, TYPE + ":" + last);
        }
    }
}
