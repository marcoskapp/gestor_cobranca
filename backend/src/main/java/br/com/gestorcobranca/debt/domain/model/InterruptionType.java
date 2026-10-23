package br.com.gestorcobranca.debt.domain.model;

/** Ato que interrompe a prescrição. */
public enum InterruptionType {
    PROTEST,
    /** Despacho que ordena a citação. */
    CITATION,
    /** Reconhecimento inequívoco do débito pelo devedor (ex.: acordo, confissão). */
    ACKNOWLEDGEMENT,
    OTHER
}
