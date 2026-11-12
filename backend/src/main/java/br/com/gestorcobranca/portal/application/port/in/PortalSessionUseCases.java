package br.com.gestorcobranca.portal.application.port.in;

import br.com.gestorcobranca.portal.application.port.in.PortalAccessUseCases.SessionGrant;

import java.time.Instant;
import java.util.UUID;

/**
 * Sessão do devedor autenticado no portal (ator {@code DEBTOR}): consulta, renovação do token
 * enquanto a sessão vale, saída e reconhecimento do aviso de privacidade (exigido antes de qualquer
 * consulta de dívidas; registrado no compliance).
 */
public interface PortalSessionUseCases {

    SessionView current();

    SessionGrant refresh();

    void logout();

    /** @param noticeVersion versão exibida (precisa ser a vigente) */
    SessionView acknowledgePrivacy(int noticeVersion);

    /**
     * @param firstName          primeiro nome do devedor (minimização: nada além disso); empresa: nome fantasia ou
     *                           razão social
     * @param privacyAcknowledged aviso vigente reconhecido nesta sessão
     */
    /**
     * @param company devedor pessoa jurídica: {@code firstName} traz o nome fantasia (ou a razão social) e o aceite
     *                pede quem está negociando pela empresa
     */
    record SessionView(UUID sessionId, String slug, String displayName, String firstName, String method,
                       Instant expiresAt, long idleTimeoutSeconds, boolean privacyAcknowledged,
                       int privacyNoticeVersion, boolean company) {
    }
}
