package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.DebtProperties;
import br.com.gestorcobranca.debt.domain.model.InstrumentType;
import br.com.gestorcobranca.debt.domain.repository.OriginContractRepository;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Recálculo em lote da prescrição dos títulos de uma natureza após mudança de regra. Cada
 * execução processa um lote de contratos (cursor por id) e reenfileira a continuação, para que
 * nenhuma transação fique longa. Idempotente: recalcular um contrato já recalculado não altera
 * nada.
 */
@Component
class PrescriptionRecalculationJob implements JobHandler<PrescriptionRecalculationJob.Payload> {

    static final String TYPE = "debt.prescription.recalculate";
    private static final Logger LOG = LoggerFactory.getLogger(PrescriptionRecalculationJob.class);

    private final JobQueue jobQueue;
    private final OriginContractRepository contracts;
    private final PrescriptionRecalculator recalculator;
    private final DebtProperties properties;

    PrescriptionRecalculationJob(JobQueue jobQueue, OriginContractRepository contracts,
                                 PrescriptionRecalculator recalculator, DebtProperties properties) {
        this.jobQueue = jobQueue;
        this.contracts = contracts;
        this.recalculator = recalculator;
        this.properties = properties;
    }

    /** @param afterId último contrato processado (nulo no início) */
    record Payload(String instrumentType, UUID afterId) {
    }

    /** Agenda o recálculo da natureza (na transação corrente; deduplicado enquanto pendente). */
    void schedule(InstrumentType instrumentType) {
        jobQueue.enqueue(TYPE, new Payload(instrumentType.name(), null), null, TYPE + ":" + instrumentType.name());
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
        InstrumentType instrumentType = InstrumentType.valueOf(payload.instrumentType());
        int batch = properties.prescription().recalculationBatch();
        List<UUID> ids = contracts.findIdsByInstrumentTypeAfter(instrumentType, payload.afterId(), batch);
        int changed = 0;
        for (UUID contractId : ids) {
            changed += recalculator.refreshContract(contractId, PrescriptionRecalculator.TRIGGER_RULE_CHANGE);
        }
        LOG.atInfo().addKeyValue("instrumentType", instrumentType.name()).addKeyValue("contracts", ids.size())
                .addKeyValue("prescriptionsChanged", changed).log("Lote de recálculo de prescrição processado");
        if (ids.size() == batch) {
            UUID last = ids.getLast();
            jobQueue.enqueue(TYPE, new Payload(instrumentType.name(), last), null,
                    TYPE + ":" + instrumentType.name() + ":" + last);
        }
    }
}
