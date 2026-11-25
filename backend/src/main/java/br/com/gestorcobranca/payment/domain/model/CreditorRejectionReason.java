package br.com.gestorcobranca.payment.domain.model;

/** Motivo da recusa do informe do portal que o credor vê (a nota da revisão é interna). */
public enum CreditorRejectionReason {
    /** O comprovante não permite confirmar o pagamento (ilegível, incompleto ou de outra operação). */
    PROOF_INVALID,
    /** O valor ou a data do comprovante não conferem com o informado. */
    AMOUNT_OR_DATE_MISMATCH,
    /** Os títulos informados não correspondem ao pagamento. */
    WRONG_TITLES,
    /** Este pagamento já foi registrado. */
    DUPLICATE,
    /** A assessoria não pôde confirmar; os detalhes ficam com a assessoria. */
    OTHER
}
