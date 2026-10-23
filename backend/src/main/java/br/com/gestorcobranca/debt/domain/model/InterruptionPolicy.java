package br.com.gestorcobranca.debt.domain.model;

/** Como as interrupções reiniciam a contagem do prazo, conforme a regra cadastrada pelo tenant. */
public enum InterruptionPolicy {
    /** Somente a primeira interrupção eficaz reinicia o prazo; as seguintes são registradas sem efeito. */
    FIRST_ONLY,
    /** Toda interrupção eficaz reinicia o prazo. */
    EVERY
}
