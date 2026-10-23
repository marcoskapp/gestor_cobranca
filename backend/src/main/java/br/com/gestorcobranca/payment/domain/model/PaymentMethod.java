package br.com.gestorcobranca.payment.domain.model;

/** Meio de pagamento (texto publicado em {@code PaymentConfirmed.method}). */
public enum PaymentMethod {
    PIX, BOLETO, CARD, TRANSFER, CASH, OTHER
}
