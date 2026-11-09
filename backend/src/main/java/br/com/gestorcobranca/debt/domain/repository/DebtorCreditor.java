package br.com.gestorcobranca.debt.domain.repository;

import java.util.UUID;

/** Par devedor × credor (título ou coobrigação), para resolver os credores de vários devedores numa consulta. */
public record DebtorCreditor(UUID debtorId, UUID creditorId) {
}
