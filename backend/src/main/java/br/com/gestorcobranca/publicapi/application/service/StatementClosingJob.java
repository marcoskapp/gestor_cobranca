package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.publicapi.application.port.in.PublicApiBillingUseCases;
import br.com.gestorcobranca.publicapi.application.port.out.UsageStore;
import br.com.gestorcobranca.publicapi.domain.repository.UsageStatementRepository;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

/**
 * Fechamento automático do ciclo mensal: no dia 1 (03h40, horário de Brasília — depois da última gravação em lote
 * do mês anterior), gera a primeira revisão do extrato do mês anterior para cada credor com consumo, em todas as
 * assessorias. Credor com extrato já fechado manualmente é ignorado. Uma instância por vez (ShedLock).
 */
@Component
class StatementClosingJob {

    private static final Logger LOG = LoggerFactory.getLogger(StatementClosingJob.class);

    private final TenantDirectory tenantDirectory;
    private final TenantExecution tenantExecution;
    private final UsageStore usage;
    private final UsageStatementRepository statements;
    private final PublicApiBillingUseCases billing;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    StatementClosingJob(TenantDirectory tenantDirectory, TenantExecution tenantExecution, UsageStore usage,
                        UsageStatementRepository statements, PublicApiBillingUseCases billing,
                        MeterRegistry meterRegistry, Clock clock) {
        this.tenantDirectory = tenantDirectory;
        this.tenantExecution = tenantExecution;
        this.usage = usage;
        this.statements = statements;
        this.billing = billing;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.public-api.statement-cron:0 40 3 1 * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "publicapi.statement-closing", lockAtMostFor = "PT30M")
    void closePreviousMonth() {
        closeMonth(YearMonth.now(clock.withZone(TimeConfig.BUSINESS_ZONE)).minusMonths(1));
    }

    /** Fecha o mês informado para todas as assessorias (visível ao pacote para testes). */
    int closeMonth(YearMonth month) {
        Instant from = month.atDay(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        int closed = 0;
        for (UUID tenantId : tenantDirectory.activeTenantIds()) {
            var creditors = tenantExecution.callInTenant(tenantId, "publicapi.statement_closing",
                    () -> usage.creditorsWithUsage(from, to));
            for (UUID creditorId : creditors) {
                try {
                    boolean done = Boolean.TRUE.equals(tenantExecution.callInTenant(tenantId,
                            "publicapi.statement_closing", () -> {
                                if (statements.findLatest(creditorId, month.atDay(1)).isPresent()) {
                                    return false;
                                }
                                billing.close(creditorId, month, null);
                                return true;
                            }));
                    if (done) {
                        closed++;
                    }
                } catch (RuntimeException e) {
                    meterRegistry.counter("publicapi.statements.close_failures").increment();
                    LOG.atError().addKeyValue("tenantId", tenantId).addKeyValue("creditorId", creditorId)
                            .addKeyValue("period", month.toString()).setCause(e)
                            .log("Falha ao fechar o extrato mensal da API pública");
                }
            }
        }
        meterRegistry.counter("publicapi.statements.closed", "trigger", "scheduled").increment(closed);
        LOG.atInfo().addKeyValue("period", month.toString()).addKeyValue("statements", closed)
                .log("Extratos mensais da API pública fechados");
        return closed;
    }
}
