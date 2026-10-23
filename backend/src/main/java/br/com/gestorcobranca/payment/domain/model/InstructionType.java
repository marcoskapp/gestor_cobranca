package br.com.gestorcobranca.payment.domain.model;

/** Tipo de instrução: Pix imediato (cob), Pix com vencimento (cobv), boleto ou link de pagamento. */
public enum InstructionType {
    PIX_IMMEDIATE, PIX_DUE, BOLETO, LINK
}
