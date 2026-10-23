package br.com.gestorcobranca.payment.application.port.out;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Controle do PSP simulado (somente com {@code app.payment.sandbox.enabled=true}; nenhum bean em
 * produção). Registra pagamentos e devoluções que o {@code findPix} do provedor simulado passa a
 * devolver.
 */
public interface PixSandbox {

    /** @return transação criada (com {@code endToEndId} novo) */
    PixProvider.PixTransaction pay(UUID accountId, String txid, BigDecimal amount);

    PixProvider.PixTransaction refund(UUID accountId, String endToEndId, BigDecimal amount);
}
