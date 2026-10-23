package br.com.gestorcobranca.debt.domain.model;

/** Quem registrou a interrupção da prescrição. */
public enum InterruptionOrigin {
    /** Operador, pela API do título. */
    MANUAL,
    /** Módulo jurídico (medida extrajudicial ou andamento processual). */
    LEGAL
}
