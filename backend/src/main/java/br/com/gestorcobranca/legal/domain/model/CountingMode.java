package br.com.gestorcobranca.legal.domain.model;

/**
 * Contagem do prazo: dias úteis (prazos processuais — CPC art. 219), dias corridos (prazos
 * materiais e os fixados em dias corridos) ou data fixada (ex.: "até 10/11", designada pelo juízo).
 */
public enum CountingMode {
    BUSINESS_DAYS,
    CALENDAR_DAYS,
    FIXED_DATE
}
