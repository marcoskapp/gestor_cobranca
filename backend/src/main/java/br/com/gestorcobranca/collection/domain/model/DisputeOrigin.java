package br.com.gestorcobranca.collection.domain.model;

/** Quem abriu a contestação. */
public enum DisputeOrigin {
    /** Registrada pelo operador a pedido do devedor. */
    OPERATOR,
    /** Aberta pelo próprio devedor (portal). */
    DEBTOR,
    /** Informada pelo credor. */
    CREDITOR,
    /** Recebida por órgão de defesa do consumidor / jurídico. */
    LEGAL;

    /**
     * A resposta fundamentada do resultado vai ao devedor pela assessoria (CDC) quando foi ele quem contestou (pelo
     * operador ou pelo portal). Contestação do credor ou de órgão/jurídico é respondida a quem a abriu.
     */
    public boolean expectsDebtorResponse() {
        return this == OPERATOR || this == DEBTOR;
    }
}
