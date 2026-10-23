package br.com.gestorcobranca.legal.domain.model;

/**
 * Tratamento de títulos com a pretensão de cobrança prescrita (decisão pendente da issue #14 —
 * entendimento recente do STJ restringe a cobrança de dívida prescrita, inclusive extrajudicial).
 * Padrão conservador: {@code EXCLUDE} — o título prescrito não é elegível ao jurídico e é
 * sinalizado; {@code FLAG} o mantém, sinalizado, para análise jurídica (sem via disponível na
 * viabilidade).
 */
public enum PrescribedTitlePolicy {
    EXCLUDE,
    FLAG
}
