package br.com.gestorcobranca.campaign.domain.model;

/** Finalidade da campanha. A régua esgotada de uma campanha {@code EXTRAJUDICIAL_COLLECTION} é critério de elegibilidade jurídica (evento {@code CadenceExhausted}). */
public enum CampaignPurpose {
    EXTRAJUDICIAL_COLLECTION, REMINDER, AGREEMENT_FOLLOW_UP, INFORMATIVE
}
