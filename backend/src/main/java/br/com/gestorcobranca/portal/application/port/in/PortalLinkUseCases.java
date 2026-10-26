package br.com.gestorcobranca.portal.application.port.in;

import java.time.Instant;
import java.util.UUID;

/**
 * Link de acesso ao portal para envio automático por outro módulo (etapa de régua do campaign), com
 * as mesmas regras do link enviado pelo operador ({@link PortalOperationsUseCases#sendAccessLink}):
 * uso único, token só no fragmento da URL (o banco guarda o SHA-256), validade da configuração do
 * portal, novo link revoga os abertos do devedor, trilha {@code PORTAL_LINK_ISSUED} no log de acesso.
 * <p>
 * Fluxo, numa só transação de quem envia: {@link #issue} gera o link (ou diz por que não há portal
 * para o devedor), quem envia monta a mensagem pelo communication e {@link #recordDispatch} vincula a
 * mensagem ao link — mensagem negada pelo porteiro ou recusada revoga o link na hora. Se a transação
 * for desfeita, o link também é. A URL nunca vai para log, evento ou auditoria: só a mensagem a leva
 * (variáveis guardadas criptografadas pelo communication).
 * <p>
 * Exige {@code MESSAGE_SEND} e o credor no escopo (o ator de sistema da régua tem ambos).
 */
public interface PortalLinkUseCases {

    LinkIssue issue(IssueLinkCommand command);

    void recordDispatch(DispatchCommand command);

    /**
     * @param portfolioId carteira do caso: precisa estar habilitada no portal do credor
     * @param channel     WHATSAPP, SMS ou EMAIL (canal da mensagem que levará o link)
     * @param origin      quem pede, sem dado pessoal (ex.: {@code CAMPAIGN}); vai ao log de acesso e à auditoria
     * @param reference   referência de quem pede (ex.: {@code campaign-enrollment:<id>})
     */
    record IssueLinkCommand(UUID debtorId, UUID creditorId, UUID portfolioId, String channel, String origin,
                            String reference) {
    }

    /**
     * @param messageStatus situação da mensagem no communication ({@code FAILED} revoga o link)
     * @param origin        e {@code reference}: os mesmos do pedido do link (trilha de acesso e auditoria)
     */
    record DispatchCommand(UUID linkId, UUID messageId, String messageStatus, String origin, String reference) {
    }

    /**
     * Resultado da geração. Sem portal para o devedor → {@code unavailableReason} preenchido e o resto nulo.
     *
     * @param unavailableReason {@link #PORTAL_NOT_ENABLED}, {@link #PORTFOLIO_NOT_IN_PORTAL} ou
     *                          {@link #DEBTOR_NOT_IN_PORTAL}
     * @param url               {@code https://…/portal/<slug>/entrar#<token>} — só para a mensagem; nunca registrar
     * @param validityHours     validade do link (configuração do portal)
     */
    record LinkIssue(String unavailableReason, UUID linkId, String url, Integer validityHours, Instant expiresAt) {

        public static final String PORTAL_NOT_ENABLED = "PORTAL_NOT_ENABLED";
        public static final String PORTFOLIO_NOT_IN_PORTAL = "PORTFOLIO_NOT_IN_PORTAL";
        public static final String DEBTOR_NOT_IN_PORTAL = "DEBTOR_NOT_IN_PORTAL";

        public static LinkIssue unavailable(String reason) {
            return new LinkIssue(reason, null, null, null, null);
        }

        public boolean available() {
            return unavailableReason == null;
        }

        /** Sem a URL: o token é segredo de acesso e não pode aparecer em log por descuido. */
        @Override
        public String toString() {
            return "LinkIssue[unavailableReason=" + unavailableReason + ", linkId=" + linkId + ", url=***, validityHours="
                    + validityHours + ", expiresAt=" + expiresAt + "]";
        }
    }
}
