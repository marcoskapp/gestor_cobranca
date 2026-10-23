package br.com.gestorcobranca.collection.domain.model;

/** Motivo do encerramento do caso. */
public enum CloseReason {
    /** Todos os títulos quitados (pagamento, desconto ou baixa). */
    SETTLED,
    /** Títulos devolvidos ao credor. */
    RETURNED,
    /** Títulos cancelados pelo credor. */
    CANCELLED,
    /** Nenhum título em cobrança na carteira por motivos combinados (ou transferência de carteira). */
    NO_OPEN_TITLES,
    /** Encerramento manual por supervisor (ex.: devedor falecido, incobrável). */
    MANUAL
}
