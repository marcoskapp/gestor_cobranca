package br.com.gestorcobranca.campaign.domain.model;

/** Ciclo da campanha. Somente {@code ACTIVE} inscreve e executa etapas; {@code PAUSED} congela as inscrições (retomadas na reativação); {@code FINISHED} é final. */
public enum CampaignStatus {
    DRAFT, ACTIVE, PAUSED, FINISHED
}
