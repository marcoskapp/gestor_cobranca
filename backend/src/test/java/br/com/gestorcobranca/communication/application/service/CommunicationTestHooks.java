package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import org.springframework.context.ApplicationContext;

import java.util.UUID;

/** Acesso dos testes de integração a componentes internos do módulo (agendamentos desligados nos testes). */
public final class CommunicationTestHooks {

    private CommunicationTestHooks() {
    }

    /** Uma execução da varredura de SLA na assessoria; devolve os estouros detectados. */
    public static int scanSla(ApplicationContext context, UUID tenantId) {
        InboxSlaScanner scanner = context.getBean(InboxSlaScanner.class);
        return context.getBean(TenantExecution.class).callInTenant(tenantId, "test.sla_scan", scanner::scan);
    }
}
