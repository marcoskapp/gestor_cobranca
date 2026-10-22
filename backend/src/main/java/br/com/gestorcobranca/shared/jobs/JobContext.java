package br.com.gestorcobranca.shared.jobs;

import java.util.UUID;

/** Informações da execução corrente de um job. */
public record JobContext(UUID jobId, UUID tenantId, String type, int attempt) {
}
