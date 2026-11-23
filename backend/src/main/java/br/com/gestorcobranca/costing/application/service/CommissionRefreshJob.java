package br.com.gestorcobranca.costing.application.service;

import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Reapuração da comissão variável quando os extratos de um período mudam ({@code CommissionStatementsChanged},
 * margem-por-carteira.md §4.2). Idempotente: reapurar de novo o mesmo intervalo grava os mesmos fatos.
 */
@Component
class CommissionRefreshJob implements JobHandler<CommissionRefreshJob.Payload> {

    static final String TYPE = "costing.commission_refresh";
    private static final Logger LOG = LoggerFactory.getLogger(CommissionRefreshJob.class);

    /** @param periodStart 1º dia do período de comissão cujos extratos mudaram */
    record Payload(LocalDate periodStart) {
    }

    private final CostRefreshService refresh;

    CommissionRefreshJob(CostRefreshService refresh) {
        this.refresh = refresh;
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
        long rows = refresh.refreshCommissionPeriod(payload.periodStart());
        LOG.atDebug().addKeyValue("process", TYPE).addKeyValue("period", payload.periodStart())
                .addKeyValue("rows", rows).log("Job de reapuração da comissão concluído");
    }
}
