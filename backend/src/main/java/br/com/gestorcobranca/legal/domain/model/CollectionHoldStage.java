package br.com.gestorcobranca.legal.domain.model;

/**
 * A partir de quando o caso de cobrança fica retido no jurídico (status LEGAL no collection):
 * {@code AUTHORIZATION} (padrão) — ao entrar em atuação jurídica autorizada (EXTRAJUDICIAL ou
 * ajuizamento), mantendo a cobrança amigável durante a triagem; {@code REVIEW} — já na triagem.
 */
public enum CollectionHoldStage {
    REVIEW,
    AUTHORIZATION;

    public boolean holds(LegalStage stage) {
        return switch (this) {
            case AUTHORIZATION -> stage.isLegalAction();
            case REVIEW -> stage == LegalStage.UNDER_REVIEW || stage == LegalStage.AWAITING_AUTHORIZATION
                    || stage.isLegalAction();
        };
    }
}
