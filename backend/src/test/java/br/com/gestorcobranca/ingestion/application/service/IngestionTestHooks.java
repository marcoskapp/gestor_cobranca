package br.com.gestorcobranca.ingestion.application.service;

import org.springframework.context.ApplicationContext;

import java.util.UUID;

/**
 * Acesso dos testes de integração às tarefas periódicas do ingestion (o agendamento fica desligado nos testes). Os
 * métodos abrem a própria transação no tenant, como o job.
 */
public final class IngestionTestHooks {

    private IngestionTestHooks() {
    }

    /** Descarte dos arquivos de importação pela política IMPORT_FILES (job {@code ingestion.file-retention}). */
    public static int purgeImportFiles(ApplicationContext context, UUID tenantId) {
        return context.getBean(ImportFileRetention.class).runFor(tenantId);
    }
}
