package br.com.gestorcobranca.debt.application.port.in;

import java.util.UUID;

/**
 * Ligação do devedor com o credor (título ou coobrigação ativa), por consulta escalar sob a RLS, sem carregar entidade
 * nem conferir permissão: o chamador já autorizou a ação (H07b-07, RS-124 — o registro externo confere antes de
 * qualquer trava). Devolve só um booleano; devedor inexistente ou de outra assessoria = sem ligação.
 */
public interface DebtorCreditorLinkQueries {

    boolean linked(UUID creditorId, UUID debtorId);
}
