package br.com.gestorcobranca.communication.infrastructure.provider.email;

import br.com.gestorcobranca.communication.application.port.out.ChannelGateway;
import br.com.gestorcobranca.communication.domain.model.ProviderType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/**
 * E-mail pelo SMTP da plataforma (Spring Mail, timeouts em {@code spring.mail.properties}); o
 * remetente e a resposta são os da conta de canal do credor (ou da assessoria). O SMTP não informa
 * entrega nem leitura: a mensagem fica {@code SENT}. Bounces e reclamações dependem do provedor de
 * e-mail com notificações (Amazon SES, issue #9). Erro de autenticação ou de endereço é definitivo,
 * inclusive a recusa do destinatário pelo servidor (5xx, {@code SMTP_RECIPIENT_REJECTED}); os demais
 * são transitórios. A exceção do Spring Mail nunca vai como causa da falha transitória: a resposta do
 * servidor SMTP costuma ecoar o endereço do destinatário, que acabaria no log técnico e no
 * {@code last_error} do job (LGPD: nada de e-mail do devedor em logs).
 */
@Component
class SmtpEmailGateway implements ChannelGateway {

    private static final Logger LOG = LoggerFactory.getLogger(SmtpEmailGateway.class);
    private static final String PROVIDER = "smtp";
    private static final String OPERATION = "send_email";

    private final JavaMailSender mailSender;
    private final MeterRegistry meterRegistry;

    SmtpEmailGateway(JavaMailSender mailSender, MeterRegistry meterRegistry) {
        this.mailSender = mailSender;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.SMTP;
    }

    @Override
    public SendResult send(OutboundRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, false, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(request.senderAddress(), request.senderName(), StandardCharsets.UTF_8.name()));
            if (request.replyTo() != null) {
                helper.setReplyTo(request.replyTo());
            }
            helper.setTo(request.recipient());
            helper.setSubject(request.subject() == null ? "" : request.subject());
            helper.setText(request.text(), false);
            mime.setHeader("X-Gestor-Message-Id", request.messageId().toString());
            mailSender.send(mime);
            outcome = "success";
            return SendResult.accepted("smtp-" + request.messageId());
        } catch (MailAuthenticationException | MailParseException | MailPreparationException e) {
            outcome = "rejected";
            LOG.atWarn().addKeyValue("provider", PROVIDER).addKeyValue("error", e.getClass().getSimpleName())
                    .log("E-mail recusado de forma definitiva");
            return SendResult.rejected("SMTP_" + e.getClass().getSimpleName().replace("Exception", "").toUpperCase(),
                    "Envio recusado pelo SMTP");
        } catch (MessagingException | UnsupportedEncodingException e) {
            outcome = "rejected";
            return SendResult.rejected("SMTP_INVALID_MESSAGE", "Remetente ou destinatário inválido");
        } catch (MailSendException e) {
            if (recipientRejected(e)) {
                outcome = "rejected";
                LOG.atWarn().addKeyValue("provider", PROVIDER).addKeyValue("error", "recipient_rejected")
                        .log("E-mail recusado pelo SMTP (destinatário)");
                return SendResult.rejected("SMTP_RECIPIENT_REJECTED", "Destinatário recusado pelo servidor de e-mail");
            }
            outcome = "transient";
            throw new TransientSendException("Falha transitória no SMTP (envio)");
        } catch (MailException e) {
            outcome = "transient";
            throw new TransientSendException("Falha transitória no SMTP (" + e.getClass().getSimpleName() + ")");
        } finally {
            meterRegistry.counter("integration.calls", "provider", PROVIDER, "operation", OPERATION, "outcome", outcome)
                    .increment();
            sample.stop(meterRegistry.timer("integration.calls.duration", "provider", PROVIDER, "operation", OPERATION));
        }
    }

    /**
     * Recusa definitiva do destinatário: o Jakarta Mail marca como endereço inválido a resposta 5xx ao
     * {@code RCPT TO}; a 4xx fica como "válido, não enviado" e segue transitória.
     */
    private static boolean recipientRejected(MailSendException e) {
        for (Exception failure : e.getMessageExceptions()) {
            for (Throwable t = failure; t != null; t = t.getCause()) {
                if (t instanceof SendFailedException sfe && sfe.getInvalidAddresses() != null
                        && sfe.getInvalidAddresses().length > 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
