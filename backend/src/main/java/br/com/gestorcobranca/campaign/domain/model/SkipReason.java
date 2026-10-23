package br.com.gestorcobranca.campaign.domain.model;

/**
 * Motivos estáveis de etapa pulada ({@code campaign.step_skipped}, painel e métricas). O detalhe
 * (ex.: motivos do porteiro, motivos de inelegibilidade) vai em {@code reasonDetail}.
 */
public final class SkipReason {

    /** Caso inelegível (RB02) por motivo que não pausa a inscrição (ex.: carteira não operável). */
    public static final String CASE_INELIGIBLE = "CASE_INELIGIBLE";
    /** Condição da etapa não atendida. */
    public static final String CONDITION_NOT_MET = "CONDITION_NOT_MET";
    /** Porteiro de contato negou (restrição, limites, janela além do adiamento máximo). */
    public static final String CONTACT_DENIED = "CONTACT_DENIED";
    /** Sem contato utilizável no canal (ex.: nenhum celular marcado como WhatsApp). */
    public static final String NO_CONTACT = "NO_CONTACT";
    /** Oferta/link de pagamento indisponível (porta sem adaptador ou oferta recusada). */
    public static final String OFFER_UNAVAILABLE = "OFFER_UNAVAILABLE";
    /** Ação sem efeito no estado atual do caso (ex.: já está com o assistente). */
    public static final String NOT_APPLICABLE = "NOT_APPLICABLE";

    private SkipReason() {
    }
}
