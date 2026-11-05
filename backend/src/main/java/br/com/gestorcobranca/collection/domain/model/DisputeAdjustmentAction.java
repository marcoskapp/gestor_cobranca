package br.com.gestorcobranca.collection.domain.model;

/**
 * Ação sugerida no título a partir de uma contestação procedente ou parcialmente procedente. A sugestão não tem
 * efeito financeiro: a ação é feita pelos fluxos existentes (debt e payment), com as regras deles.
 */
public enum DisputeAdjustmentAction {
    /** Cancelar os títulos (dívida não reconhecida, fraude, prescrição, produto/serviço). */
    CANCEL_TITLES,
    /** Corrigir o valor (o credor corrige o principal na carga, com o ajuste lançado no razão). */
    ADJUST_AMOUNT,
    /** Baixar por pagamento comprovado: pagamento externo com comprovante, revisado conforme a regra de pagamentos. */
    SETTLE_BY_PAYMENT;

    /** Sugestão padrão pelo tipo e pelo resultado; nula quando o resultado não pede ajuste. */
    public static DisputeAdjustmentAction suggestedFor(DisputeType type, DisputeOutcome outcome) {
        if (outcome == null || outcome == DisputeOutcome.REJECTED) {
            return null;
        }
        return switch (type) {
            case ALREADY_PAID -> SETTLE_BY_PAYMENT;
            case WRONG_AMOUNT -> ADJUST_AMOUNT;
            case NOT_RECOGNIZED, FRAUD, PRESCRIBED, PRODUCT_OR_SERVICE ->
                    outcome == DisputeOutcome.PARTIALLY_UPHELD ? ADJUST_AMOUNT : CANCEL_TITLES;
            case OTHER -> outcome == DisputeOutcome.PARTIALLY_UPHELD ? ADJUST_AMOUNT : CANCEL_TITLES;
        };
    }
}
