package br.com.gestorcobranca.legal.domain.model;

/**
 * Quem conduz os casos jurídicos (decisão pendente da issue #14). Padrão conservador:
 * {@code INTERNAL_ONLY} — escritórios externos só passam a acessar a plataforma quando a
 * assessoria habilitar.
 */
public enum ConductModel {
    INTERNAL_ONLY,
    LAW_FIRMS_ONLY,
    BOTH;

    public boolean allowsInternal() {
        return this != LAW_FIRMS_ONLY;
    }

    public boolean allowsLawFirms() {
        return this != INTERNAL_ONLY;
    }
}
