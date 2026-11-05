package br.com.gestorcobranca.collection.application.port.out;

import java.util.List;
import java.util.UUID;

/**
 * Envio de e-mail da rotina de contestações ao credor (SMTP da plataforma; compatível com o Amazon SES SMTP).
 * O servidor de e-mail só informa o aceite: aceito = enviado (sem confirmação de entrega). Falha transitória →
 * {@link TransientMailException} (o job tenta de novo); recusa definitiva → {@link Result#rejected}.
 */
public interface DisputeMailer {

    Result send(OutgoingMail mail);

    /**
     * @param replyTo       e-mail do responsável (as respostas do credor chegam a ele)
     * @param correlationId id interno do e-mail (cabeçalho de correlação; nunca dado pessoal)
     */
    record OutgoingMail(String senderName, List<String> to, List<String> cc, String replyTo, String subject,
                        String text, UUID correlationId) {

        public OutgoingMail {
            to = List.copyOf(to);
            cc = cc == null ? List.of() : List.copyOf(cc);
        }
    }

    record Result(boolean accepted, String errorCode) {

        public static Result ok() {
            return new Result(true, null);
        }

        public static Result rejected(String code) {
            return new Result(false, code);
        }
    }

    class TransientMailException extends RuntimeException {

        public TransientMailException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
