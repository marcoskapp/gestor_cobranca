package br.com.gestorcobranca.settlement.domain.model;

/** Recebimento confirmado ({@code PAYMENT}) ou compensação de estorno/realocação ({@code REVERSAL}, negativo). */
public enum ReceiptKind {
    PAYMENT,
    REVERSAL
}
