package br.com.gestorcobranca.publicapi.application.service.platform;

import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantView;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Fechamento automático dos ciclos da cobrança da plataforma: verificação diária de madrugada (02h40, horário de
 * Brasília — depois da virada do ciclo às 00:00, da última gravação em lote do dia e do fechamento dos extratos dos
 * credores) que fecha, em cada assessoria ativa com política da plataforma, os ciclos encerrados ainda sem extrato —
 * também de quem não usou a API no ciclo, quando há mensalidade. Executa como ator de sistema da própria assessoria
 * (RLS); uma instância por vez (ShedLock), e o bloqueio da política torna o fechamento idempotente mesmo junto com um
 * fechamento manual. A falha de uma assessoria não impede as demais e é contada em métrica.
 */
@Component
class PlatformStatementClosingJob {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformStatementClosingJob.class);
    private static final String OPERATION = "publicapi.platform_statement_closing";

    private final TenantDirectory tenantDirectory;
    private final TenantAdministration tenantAdministration;
    private final TenantExecution tenantExecution;
    private final PlatformBillingEngine engine;
    private final MeterRegistry meterRegistry;

    PlatformStatementClosingJob(TenantDirectory tenantDirectory, TenantAdministration tenantAdministration,
                                TenantExecution tenantExecution, PlatformBillingEngine engine,
                                MeterRegistry meterRegistry) {
        this.tenantDirectory = tenantDirectory;
        this.tenantAdministration = tenantAdministration;
        this.tenantExecution = tenantExecution;
        this.engine = engine;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(cron = "${app.public-api.platform-statement-cron:0 40 2 * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "publicapi.platform-statement-closing", lockAtMostFor = "PT30M")
    void closeEndedCycles() {
        closeAll();
    }

    /** Fecha os ciclos encerrados de todas as assessorias (visível ao pacote para testes). */
    int closeAll() {
        Timer.Sample sample = Timer.start(meterRegistry);
        int closed = 0;
        int processed = 0;
        int failures = 0;
        for (UUID tenantId : tenantDirectory.activeTenantIds()) {
            if (tenantAdministration.findById(tenantId).map(TenantView::platform).orElse(true)) {
                continue;
            }
            processed++;
            try {
                Integer done = tenantExecution.callInTenant(tenantId, OPERATION, engine::closeEndedCycles);
                closed += done == null ? 0 : done;
            } catch (RuntimeException e) {
                failures++;
                meterRegistry.counter("publicapi.platform_statements.close_failures").increment();
                LOG.atError().addKeyValue("tenantId", tenantId).setCause(e)
                        .log("Falha ao fechar os ciclos da cobrança da plataforma");
            }
        }
        long nanos = sample.stop(Timer.builder("publicapi.platform_billing.closing.duration").register(meterRegistry));
        meterRegistry.counter("publicapi.platform_billing.closing.tenants_processed").increment(processed);
        LOG.atInfo().addKeyValue("tenantsProcessed", processed).addKeyValue("statements", closed)
                .addKeyValue("failures", failures).addKeyValue("durationMs", nanos / 1_000_000)
                .log("Ciclos da cobrança da plataforma verificados");
        return closed;
    }
}
