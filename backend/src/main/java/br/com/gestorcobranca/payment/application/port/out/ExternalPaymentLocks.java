package br.com.gestorcobranca.payment.application.port.out;

import java.util.UUID;

/**
 * Serializa o registro de pagamentos externos que entram na mesma soma acumulada (mesmo usuário que
 * informa, mesmo devedor no credor): dois registros simultâneos não podem, cada um abaixo do limite,
 * somar acima dele sem revisão. Locks transacionais, liberados no commit/rollback; sempre adquiridos na
 * mesma ordem (quem informa, depois o devedor).
 */
public interface ExternalPaymentLocks {

    void lockExternalRegistration(UUID tenantId, UUID informedBy, UUID creditorId, UUID debtorId);
}
