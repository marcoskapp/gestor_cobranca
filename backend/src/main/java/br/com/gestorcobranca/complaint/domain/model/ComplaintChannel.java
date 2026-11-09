package br.com.gestorcobranca.complaint.domain.model;

/** Por onde a reclamação chegou à assessoria. */
public enum ComplaintChannel {
    /** Procon (notificação ou audiência de conciliação). */
    PROCON,
    /** Plataforma consumidor.gov.br. */
    CONSUMIDOR_GOV,
    /** Banco Central (RDR) — normalmente encaminhada pela instituição credora. */
    BACEN,
    /** Ouvidoria ou SAC do credor que repassou a reclamação. */
    CREDITOR_OMBUDSMAN,
    /** Reclame Aqui. */
    RECLAME_AQUI,
    /** Outro canal (ex.: Ministério Público, Defensoria, e-mail ao credor). */
    OTHER
}
