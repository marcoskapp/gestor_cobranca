package br.com.gestorcobranca.debt.domain.repository;

import java.time.Instant;
import java.util.UUID;

/** Devedor candidato à retenção e o instante da sua última movimentação. */
public record InactiveDebtorCandidate(UUID debtorId, Instant lastActivityAt) {
}
