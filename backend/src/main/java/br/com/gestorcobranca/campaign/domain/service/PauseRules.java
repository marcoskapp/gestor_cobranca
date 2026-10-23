package br.com.gestorcobranca.campaign.domain.service;

import br.com.gestorcobranca.campaign.domain.model.AudienceSnapshot;
import br.com.gestorcobranca.campaign.domain.model.CampaignPurpose;
import br.com.gestorcobranca.campaign.domain.model.PauseReason;

import java.util.Optional;

/**
 * Situações do caso que suspendem a régua (pausa automática), lidas da posição atual: jurídico,
 * suspensão, contestação bloqueante, acordo em vigor e todos os canais restritos. A retomada
 * automática acontece quando nenhuma delas persiste.
 * <p>
 * Campanhas de acompanhamento de acordo ({@code AGREEMENT_FOLLOW_UP}, ex.: lembrete de parcela) não
 * são pausadas pelo acordo em vigor nem por pagamentos — é justamente o seu público.
 */
public final class PauseRules {

    private PauseRules() {
    }

    /** Primeiro motivo que impede a régua de seguir, na ordem de precedência. */
    public static Optional<PauseReason> blocking(AudienceSnapshot s, CampaignPurpose purpose) {
        if ("LEGAL".equals(s.status())) {
            return Optional.of(PauseReason.CASE_LEGAL);
        }
        if ("SUSPENDED".equals(s.status())) {
            return Optional.of(PauseReason.CASE_SUSPENDED);
        }
        if ("DISPUTED".equals(s.status()) || s.eligibilityReasons().contains("DISPUTE_OPEN")) {
            return Optional.of(PauseReason.DISPUTE_OPENED);
        }
        if (s.activeAgreement() && pausesOnAgreement(purpose)) {
            return Optional.of(PauseReason.AGREEMENT_FORMALIZED);
        }
        if (s.eligibilityReasons().contains("CONTACT_RESTRICTED")) {
            return Optional.of(PauseReason.CONTACT_RESTRICTED);
        }
        return Optional.empty();
    }

    /** A pausa pode ser retomada automaticamente com esta posição do caso. */
    public static boolean cleared(PauseReason reason, AudienceSnapshot s, CampaignPurpose purpose) {
        return reason.resumesWhenCleared() && s.active() && blocking(s, purpose).isEmpty();
    }

    public static boolean pausesOnAgreement(CampaignPurpose purpose) {
        return purpose != CampaignPurpose.AGREEMENT_FOLLOW_UP;
    }

    public static boolean pausesOnPayment(CampaignPurpose purpose) {
        return purpose != CampaignPurpose.AGREEMENT_FOLLOW_UP;
    }
}
