package br.com.gestorcobranca.costing.application.service;

import br.com.gestorcobranca.shared.config.AsyncConfig;
import br.com.gestorcobranca.shared.events.TenantEventListener;
import br.com.gestorcobranca.shared.events.incentive.CommissionStatementsChanged;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Reações do costing a eventos de outros módulos. */
@Component
class CostingEventListeners extends TenantEventListener {

    /**
     * Janela de agrupamento: avisos do mesmo período na mesma janela (fechamento, ajustes e recálculos em sequência)
     * viram uma reapuração só, que roda quando a janela termina. A chave leva a janela porque a fila também deduplica
     * contra job em execução: como o job da janela só começa depois dela (mais {@link #CLOCK_MARGIN}), um aviso não
     * cai num job que já leu os extratos (o aviso seguinte abre outra janela e outro job).
     */
    static final Duration COMMISSION_REFRESH_WINDOW = Duration.ofMinutes(2);
    /**
     * Folga entre o fim da janela (relógio da aplicação) e o início do job (a fila reivindica pelo relógio do banco):
     * cobre diferença de até 10 s entre os relógios. Premissa: relógios da aplicação e do banco sincronizados (NTP).
     */
    static final Duration CLOCK_MARGIN = Duration.ofSeconds(10);

    private final JobQueue jobs;
    private final Clock clock;

    CostingEventListeners(JobQueue jobs, Clock clock) {
        this.jobs = jobs;
        this.clock = clock;
    }

    /** Extratos de comissão do período mudaram (incentive): reapura a comissão variável da margem (§4.2). */
    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(CommissionStatementsChanged event) {
        handleOnce(event, "costing.commission-statements", () -> {
            long window = Instant.now(clock).toEpochMilli() / COMMISSION_REFRESH_WINDOW.toMillis();
            Instant runAt = Instant.ofEpochMilli((window + 1) * COMMISSION_REFRESH_WINDOW.toMillis()).plus(CLOCK_MARGIN);
            jobs.enqueue(CommissionRefreshJob.TYPE, new CommissionRefreshJob.Payload(event.periodStart()), runAt,
                    "commission:" + event.periodStart() + ":" + window);
        });
    }
}
