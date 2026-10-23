package br.com.gestorcobranca.payment.application.port.in;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Simulação do PSP para ambiente local e testes (provedor {@code SANDBOX}). Disponível apenas com
 * {@code app.payment.sandbox.enabled=true}, que é recusado no perfil {@code prod}.
 */
public interface PaymentSandboxUseCases {

    /**
     * Simula o pagamento de uma instrução Pix ativa numa conta sandbox: registra a transação no PSP
     * simulado e monta a notificação (corpo da API Pix), assinada com o segredo HMAC da conta.
     *
     * @param amount  nulo = valor da instrução
     * @param deliver entrega a notificação ao webhook (mesmo fluxo de produção)
     */
    SimulatedNotification simulatePixPayment(UUID instructionId, BigDecimal amount, boolean deliver);

    /** Simula uma devolução (total ou parcial) de um Pix recebido. */
    SimulatedNotification simulatePixRefund(String endToEndId, BigDecimal amount, boolean deliver);

    /** @param signature cabeçalho {@code X-Pix-Signature} para o corpo (modo HMAC) */
    record SimulatedNotification(String endToEndId, String txid, String routingKey, String body, String signature,
                                 PixWebhookUseCases.Outcome outcome) {
    }
}
