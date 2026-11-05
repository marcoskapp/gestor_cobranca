package br.com.gestorcobranca.collection.domain.service;

import br.com.gestorcobranca.collection.domain.model.DisputeMailReply.Verdicts;
import br.com.gestorcobranca.collection.domain.model.MailQuarantineReason;
import br.com.gestorcobranca.collection.domain.model.MailVerdict;

import java.util.Optional;

/**
 * Triagem do e-mail do credor recebido para um pedido, na ordem de gravidade: ilegível, vírus, autenticação
 * (falsificação), contestação encerrada, endereço vencido, remetente não cadastrado, spam, excesso do remetente,
 * tamanho e quantidade de anexos. O primeiro motivo encontrado manda o e-mail para a quarentena; nenhum motivo = aguarda
 * o analista confirmar a posição do credor.
 * <p>
 * Autenticação: vale o DMARC PASS (alinhamento do domínio do remetente com SPF ou DKIM). Sem política DMARC publicada
 * no domínio ({@code GRAY}), exige SPF e DKIM PASS. Qualquer outro caso (FAIL, falha de processamento, verificação
 * desligada) é falha de autenticação.
 */
public final class MailReplyScreening {

    private MailReplyScreening() {
    }

    /**
     * @param malformed        a mensagem não pôde ser lida
     * @param disputeOpen      a contestação ainda está aberta
     * @param routeValid       o endereço de resposta ainda vale
     * @param senderRegistered o remetente é contato de contestação ativo do credor do pedido
     * @param recentFromSender e-mails já recebidos deste remetente na última hora
     */
    public static Optional<MailQuarantineReason> screen(Verdicts verdicts, boolean malformed, boolean disputeOpen,
                                                        boolean routeValid, boolean senderRegistered,
                                                        long recentFromSender, Limits limits, long size,
                                                        int attachments) {
        if (malformed) {
            return Optional.of(MailQuarantineReason.MALFORMED);
        }
        if (verdicts.virus() != MailVerdict.PASS) {
            return Optional.of(MailQuarantineReason.VIRUS);
        }
        if (!authenticated(verdicts)) {
            return Optional.of(MailQuarantineReason.AUTHENTICATION_FAILED);
        }
        if (!disputeOpen) {
            return Optional.of(MailQuarantineReason.DISPUTE_CLOSED);
        }
        if (!routeValid) {
            return Optional.of(MailQuarantineReason.ROUTE_EXPIRED);
        }
        if (!senderRegistered) {
            return Optional.of(MailQuarantineReason.SENDER_NOT_REGISTERED);
        }
        if (verdicts.spam() == MailVerdict.FAIL) {
            return Optional.of(MailQuarantineReason.SPAM);
        }
        if (recentFromSender >= limits.perSenderPerHour()) {
            return Optional.of(MailQuarantineReason.RATE_LIMITED);
        }
        if (size > limits.maxMessageBytes()) {
            return Optional.of(MailQuarantineReason.TOO_LARGE);
        }
        if (attachments > limits.maxAttachments()) {
            return Optional.of(MailQuarantineReason.TOO_MANY_ATTACHMENTS);
        }
        return Optional.empty();
    }

    /** DMARC PASS; ou, sem política DMARC no domínio, SPF e DKIM PASS. */
    public static boolean authenticated(Verdicts verdicts) {
        if (verdicts.dmarc() == MailVerdict.PASS) {
            return true;
        }
        return verdicts.dmarc() == MailVerdict.GRAY && verdicts.spf() == MailVerdict.PASS
                && verdicts.dkim() == MailVerdict.PASS;
    }

    public record Limits(int perSenderPerHour, long maxMessageBytes, int maxAttachments) {
    }
}
