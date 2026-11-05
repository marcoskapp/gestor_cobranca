package br.com.gestorcobranca.collection.infrastructure.provider;

import br.com.gestorcobranca.collection.application.port.out.DisputeMailer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/**
 * E-mail das contestações ao credor pelo SMTP da plataforma (Spring Mail, timeouts em {@code spring.mail.properties};
 * Amazon SES SMTP em produção). Remetente da plataforma ({@code app.mail.from}) com o nome da assessoria; resposta
 * para o responsável. O SMTP não informa entrega: aceito = enviado. Erro de autenticação, de endereço ou de montagem
 * é definitivo; os demais são transitórios. Métrica {@code integration.calls{provider=smtp,operation=dispute_mail}}.
 */
@Component
class SmtpDisputeMailer implements DisputeMailer {

    private static final Logger LOG = LoggerFactory.getLogger(SmtpDisputeMailer.class);
    private static final String PROVIDER = "smtp";
    private static final String OPERATION = "dispute_mail";

    private final JavaMailSender mailSender;
    private final MeterRegistry meterRegistry;
    private final String from;

    SmtpDisputeMailer(JavaMailSender mailSender, MeterRegistry meterRegistry,
                      @Value("${app.mail.from:no-reply@gestorcobranca.com.br}") String from) {
        this.mailSender = mailSender;
        this.meterRegistry = meterRegistry;
        this.from = from;
    }

    @Override
    public Result send(OutgoingMail mail) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, false, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(from, mail.senderName(), StandardCharsets.UTF_8.name()));
            if (mail.replyTo() != null) {
                helper.setReplyTo(mail.replyTo());
            }
            helper.setTo(mail.to().toArray(String[]::new));
            if (!mail.cc().isEmpty()) {
                helper.setCc(mail.cc().toArray(String[]::new));
            }
            helper.setSubject(mail.subject());
            helper.setText(mail.text(), false);
            mime.setHeader("X-Gestor-Dispute-Mail-Id", mail.correlationId().toString());
            mailSender.send(mime);
            outcome = "success";
            return Result.ok();
        } catch (MailAuthenticationException | MailParseException | MailPreparationException e) {
            outcome = "rejected";
            LOG.atWarn().addKeyValue("provider", PROVIDER).addKeyValue("error", e.getClass().getSimpleName())
                    .log("E-mail da contestação recusado de forma definitiva");
            return Result.rejected("SMTP_" + e.getClass().getSimpleName().replace("Exception", "").toUpperCase());
        } catch (MessagingException | UnsupportedEncodingException e) {
            outcome = "rejected";
            return Result.rejected("SMTP_INVALID_MESSAGE");
        } catch (MailException e) {
            outcome = "transient";
            throw new TransientMailException("Falha transitória no SMTP", e);
        } finally {
            meterRegistry.counter("integration.calls", "provider", PROVIDER, "operation", OPERATION, "outcome", outcome)
                    .increment();
            sample.stop(meterRegistry.timer("integration.calls.duration", "provider", PROVIDER, "operation", OPERATION));
        }
    }
}
