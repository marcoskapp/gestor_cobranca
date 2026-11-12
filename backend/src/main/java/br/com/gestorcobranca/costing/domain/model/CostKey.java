package br.com.gestorcobranca.costing.domain.model;

import java.time.LocalDate;
import java.util.UUID;

/** Dia × credor × carteira (credor e carteira podem ser nulos: valor sem credor ou sem carteira). */
public record CostKey(LocalDate day, UUID creditorId, UUID portfolioId) {
}
