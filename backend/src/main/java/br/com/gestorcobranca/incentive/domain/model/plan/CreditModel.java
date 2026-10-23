package br.com.gestorcobranca.incentive.domain.model.plan;

/** Quem recebe as parcelas pagas depois de mudança de carteira (§3.3). */
public enum CreditModel {
    /** Quem fechou o acordo, mesmo que o caso tenha sido redistribuído (padrão). */
    ORIGINATOR,
    /** O responsável atual do caso na data do pagamento. */
    PORTFOLIO
}
