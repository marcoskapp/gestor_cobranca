package br.com.gestorcobranca.portal.application.port.in;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Acesso público ao portal de um credor (sem autenticação; protegido por limite por IP, bloqueio
 * progressivo por documento e IP e respostas idênticas para documento inexistente).
 * <ul>
 *   <li>{@link #requestCode}: CPF/CNPJ + canal → código enviado ao contato cadastrado pelo
 *       communication (que passa pelo porteiro). A resposta é a mesma exista ou não o documento.</li>
 *   <li>{@link #verify}: código (e o dado de conhecimento, se o credor exigir) → sessão do portal.</li>
 *   <li>{@link #redeemLink}: link de uso único → sessão (ou a etapa de conhecimento, se exigida).</li>
 * </ul>
 * Falhas são sempre {@code portal.access_invalid} (401), sem dizer o que estava errado; bloqueio
 * → {@link AccessLockedException} (429).
 */
public interface PortalAccessUseCases {

    /** Marca e textos públicos do portal (portal inexistente ou desabilitado → 404). */
    SiteView site(String slug);

    /** Logotipo público do portal (404 sem logotipo). O chamador fecha o stream. */
    LogoContent logo(String slug);

    ChallengeView requestCode(String slug, RequestCodeCommand command, ClientInfo client);

    SessionGrant verify(String slug, UUID challengeId, VerifyCommand command, ClientInfo client);

    LinkResult redeemLink(String slug, String token, ClientInfo client);

    /** @param ip IP de origem (já considerando o proxy); @param userAgent agente de usuário */
    record ClientInfo(String ip, String userAgent) {
    }

    /** @param channel WHATSAPP, SMS ou EMAIL (entre os habilitados no portal) */
    record RequestCodeCommand(String document, String channel) {
    }

    /** @param code código recebido (vazio na etapa de conhecimento após o link) */
    record VerifyCommand(String code, String knowledgeAnswer) {
    }

    /**
     * @param knowledgeChallenge NONE, BIRTH_DATE ou CONTRACT_SUFFIX — o que será pedido junto do código
     * @param codeRequired       falso na etapa de conhecimento após o link
     */
    record ChallengeView(UUID challengeId, Instant expiresAt, String channel, String knowledgeChallenge,
                         boolean codeRequired, int codeLength) {
    }

    record SessionGrant(String accessToken, Instant expiresAt, UUID sessionId) {
    }

    /** Um dos dois: a sessão ou a etapa de conhecimento. */
    record LinkResult(SessionGrant session, ChallengeView knowledgeStep) {
    }

    /**
     * @param primaryColor   cor de botões (contraste ≥ 4,5:1 com branco)
     * @param accentColor    cor de destaque (≥ 3:1)
     * @param hasLogo        há logotipo em {@code /sites/{slug}/logo}
     * @param otpChannels    canais oferecidos para o código
     * @param privacyNotice  texto do aviso vigente e sua versão
     */
    record SiteView(String slug, String displayName, String primaryColor, String accentColor, boolean hasLogo,
                    String logoVersion, List<String> otpChannels, String knowledgeChallenge, String supportText,
                    int privacyNoticeVersion, String privacyNotice) {
    }

    record LogoContent(String contentType, String sha256, InputStream content) {
    }

    /** Acesso temporariamente bloqueado (documento ou IP), até {@link #until()}. */
    final class AccessLockedException extends RuntimeException {

        private final transient Instant until;

        public AccessLockedException(Instant until) {
            super("Muitas tentativas. Aguarde alguns minutos e tente de novo.");
            this.until = until;
        }

        public Instant until() {
            return until;
        }
    }
}
