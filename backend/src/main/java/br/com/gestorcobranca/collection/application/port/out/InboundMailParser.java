package br.com.gestorcobranca.collection.application.port.out;

import java.util.List;

/**
 * Leitura do e-mail recebido (MIME): remetente, identificadores, assunto, texto — o HTML vira texto puro com lista de
 * permissões, nada é renderizado — e anexos. O original continua guardado como veio, para a cadeia de custódia.
 */
public interface InboundMailParser {

    /** Nunca lança por conteúdo inválido: devolve {@link ParsedMail#malformed()} verdadeiro. */
    ParsedMail parse(byte[] raw, int maxTextChars, int maxAttachments);

    /**
     * @param from          endereço do remetente (cabeçalho From, minúsculo; nulo se ausente ou inválido)
     * @param messageId     cabeçalho Message-ID (nulo se ausente)
     * @param recipients    endereços dos cabeçalhos To, Cc e Delivered-To (para o adaptador local)
     * @param textTruncated o texto passou do limite e foi cortado
     * @param attachments   anexos lidos (até o limite + 1, para acusar o excesso)
     */
    record ParsedMail(boolean malformed, String from, String messageId, String inReplyTo, List<String> recipients,
                      String subject, String text, boolean textTruncated, List<Attachment> attachments,
                      int attachmentCount) {

        public ParsedMail {
            recipients = recipients == null ? List.of() : List.copyOf(recipients);
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        public static ParsedMail invalid() {
            return new ParsedMail(true, null, null, null, List.of(), null, null, false, List.of(), 0);
        }
    }

    /** @param declaredType tipo declarado no MIME (só informativo: o tipo vale pelo conteúdo) */
    record Attachment(String fileName, String declaredType, byte[] content) {
    }
}
