package br.com.gestorcobranca.collection.application.port.out;

import java.util.UUID;

/**
 * Exclusão mútua por (tenant, devedor) dentro da transação corrente: serializa as alterações dos
 * casos do devedor feitas por consumidores de eventos (consumidos em paralelo) e por comandos de
 * usuários, para que dois eventos de títulos não abram o mesmo caso duas vezes e para que um
 * consumo e um comando simultâneos não colidam no lock otimista. Deve ser obtido <b>antes</b> de
 * carregar o caso. Liberado ao fim da transação.
 */
public interface DebtorCaseLock {

    void lock(UUID tenantId, UUID debtorId);
}
