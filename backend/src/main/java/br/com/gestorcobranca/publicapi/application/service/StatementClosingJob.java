package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.publicapi.application.port.in.PublicApiBillingUseCases;
import br.com.gestorcobranca.publicapi.domain.repository.IntegrationTokenRepository;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Fechamento automático dos ciclos de consumo: verificação diária de madrugada (02h20, horário de Brasília — depois
 * da virada do ciclo às 00:00 e da última gravação em lote do dia anterior) que fecha, para cada credor com token de
 * integração em todas as assessorias, os ciclos já encerrados ainda sem extrato (cada credor tem o próprio dia de
 * fechamento, definido no contrato). Ciclo já fechado manualmente é ignorado. Uma instância por vez (ShedLock); a
 * falha de um credor não impede os demais e é contada em métrica.
 */
@Component
class StatementClosingJob {

    private static final Logger LOG = LoggerFactory.getLogger(StatementClosingJob.class);
    private static final String OPERATION = "publicapi.statement_closing";

    private final TenantDirectory tenantDirectory;
    private final TenantExecution tenantExecution;
    private final IntegrationTokenRepository tokens;
    private final PublicApiBillingUseCases billing;
    private final MeterRegistry meterRegistry;

    StatementClosingJob(TenantDirectory tenantDirectory, TenantExecution tenantExecution,
                        IntegrationTokenRepository tokens, PublicApiBillingUseCases billing,
                        MeterRegistry meterRegistry) {
        this.tenantDirectory = tenantDirectory;
        this.tenantExecution = tenantExecution;
        this.tokens = tokens;
        this.billing = billing;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(cron = "${app.public-api.statement-cron:0 20 2 * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "publicapi.statement-closing", lockAtMostFor = "PT30M")
    void closeEndedCycles() {
        closeAll();
    }

    /** Fecha os ciclos encerrados de todos os credores, em todas as assessorias (visível ao pacote para testes). */
    int closeAll() {
        int closed = 0;
        int creditorsChecked = 0;
        for (UUID tenantId : tenantDirectory.activeTenantIds()) {
            List<UUID> creditors = tenantExecution.callInTenant(tenantId, OPERATION, tokens::findDistinctCreditorIds);
            for (UUID creditorId : creditors) {
                creditorsChecked++;
                try {
                    Integer done = tenantExecution.callInTenant(tenantId, OPERATION,
                            () -> billing.closeEndedCycles(creditorId));
                    closed += done == null ? 0 : done;
                } catch (RuntimeException e) {
                    meterRegistry.counter("publicapi.statements.close_failures").increment();
                    LOG.atError().addKeyValue("tenantId", tenantId).addKeyValue("creditorId", creditorId).setCause(e)
                            .log("Falha ao fechar ciclos de consumo da API pública");
                }
            }
        }
        meterRegistry.counter("publicapi.statements.closed", "trigger", "scheduled").increment(closed);
        LOG.atInfo().addKeyValue("creditorsChecked", creditorsChecked).addKeyValue("statements", closed)
                .log("Ciclos de consumo da API pública verificados");
        return closed;
    }
}
