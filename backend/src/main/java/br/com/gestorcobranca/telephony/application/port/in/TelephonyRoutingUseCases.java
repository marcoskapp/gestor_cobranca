package br.com.gestorcobranca.telephony.application.port.in;

import java.util.List;
import java.util.UUID;

/**
 * Roteamento entre contas de telefonia: uma regra por escopo — campanha de discagem, carteira,
 * credor ou padrão da assessoria — com a conta principal e, opcionalmente, a de contingência
 * (assume quando a principal está inativa ou falha de forma transitória sem ter criado a chamada).
 * A discagem usa a regra mais específica com conta utilizável. Conta restrita a um credor só serve a
 * regras desse credor. Alterações auditadas.
 */
public interface TelephonyRoutingUseCases {

    List<RouteView> list();

    /** Cria ou substitui a regra do escopo. */
    RouteView save(SaveRouteCommand command);

    void delete(UUID routeId);

    /**
     * @param scopeType {@code DEFAULT}, {@code CREDITOR}, {@code PORTFOLIO} ou {@code CAMPAIGN}
     * @param scopeId   credor, carteira ou campanha; nulo no padrão
     */
    record SaveRouteCommand(String scopeType, UUID scopeId, UUID primaryAccountId, UUID fallbackAccountId) {
    }

    /** @param creditorId credor do escopo (nulo no padrão e em campanha sem credor) */
    record RouteView(UUID id, String scopeType, UUID scopeId, UUID creditorId, UUID primaryAccountId,
                     UUID fallbackAccountId, long version) {
    }
}
