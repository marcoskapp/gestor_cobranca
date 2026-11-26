package br.com.gestorcobranca.compliance.domain.repository;

import java.time.Instant;
import java.util.UUID;

/** Avisos ao credor pendentes de um credor (consulta agrupada). */
public record PendingNoticeCount(UUID creditorId, long pending, Instant oldestErasedAt) {
}
