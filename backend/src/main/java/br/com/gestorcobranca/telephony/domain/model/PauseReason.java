package br.com.gestorcobranca.telephony.domain.model;

/**
 * Motivos de pausa (catálogo fixo, padrão de mercado de centrais de cobrança). {@code productive}
 * indica pausa considerada trabalho (treinamento, feedback, retorno manual) nos indicadores.
 */
public enum PauseReason {
    BREAK(false),
    LUNCH(false),
    RESTROOM(false),
    TRAINING(true),
    FEEDBACK(true),
    MEETING(true),
    BACKOFFICE(true),
    TECHNICAL_ISSUE(false),
    OTHER(false);

    private final boolean productive;

    PauseReason(boolean productive) {
        this.productive = productive;
    }

    public boolean isProductive() {
        return productive;
    }
}
