package br.com.gestorcobranca.negotiation.domain.model.offer;

/** Natureza de uma violação apontada pela validação de condições. */
public enum ViolationKind {
    /** Fora da política vigente (envelope, parcelamento, prazos): nunca pode ser concedido. */
    POLICY,
    /** Dentro da política, mas acima da alçada do ator: exige aprovação (ou transferência, no assistente). */
    AUTHORITY,
    /** Títulos ou caso inelegíveis (status, contestação, carteira não operável). */
    ELIGIBILITY
}
