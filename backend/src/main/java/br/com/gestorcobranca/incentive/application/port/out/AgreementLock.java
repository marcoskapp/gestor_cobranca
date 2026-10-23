package br.com.gestorcobranca.incentive.application.port.out;

import java.util.UUID;

/**
 * Serializa, até o fim da transação, o processamento de fatos do mesmo acordo (atribuição,
 * pagamentos e estornos chegam em paralelo e em qualquer ordem). Implementação com advisory lock
 * transacional do PostgreSQL.
 */
public interface AgreementLock {

    void lock(UUID agreementId);
}
