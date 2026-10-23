package br.com.gestorcobranca.campaign.domain.model;

/** Situação da inscrição. {@code FAILED}: etapa com erro aguardando reprocessamento; {@code HOLDOUT}: grupo de controle do teste A/B (nenhuma ação, só observação). */
public enum EnrollmentStatus {
    ACTIVE, PAUSED, FAILED, HOLDOUT, ENDED
}
