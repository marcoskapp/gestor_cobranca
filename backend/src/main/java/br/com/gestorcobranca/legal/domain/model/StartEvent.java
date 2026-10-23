package br.com.gestorcobranca.legal.domain.model;

/**
 * Fato que inicia a contagem (CPC art. 224): intimação/ciência (a contagem começa no primeiro
 * dia útil seguinte) ou disponibilização no Diário de Justiça eletrônico (a publicação é o
 * primeiro dia útil seguinte à disponibilização e a contagem começa no dia útil seguinte à
 * publicação — §§ 2º e 3º).
 */
public enum StartEvent {
    INTIMATION,
    DJE_AVAILABILITY
}
