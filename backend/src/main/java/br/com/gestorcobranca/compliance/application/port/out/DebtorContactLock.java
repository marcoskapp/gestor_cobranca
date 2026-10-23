package br.com.gestorcobranca.compliance.application.port.out;

import java.util.UUID;

/**
 * Exclusão mútua por devedor dentro da transação corrente: serializa a avaliação e o registro
 * de tentativas de contato do mesmo devedor, de modo que envios simultâneos (campanha e
 * operador, duas instâncias) não ultrapassem os limites da política. Liberado ao fim da
 * transação.
 */
public interface DebtorContactLock {

    void lock(UUID tenantId, UUID debtorId);
}
