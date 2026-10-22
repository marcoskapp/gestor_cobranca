package br.com.gestorcobranca.identity.infrastructure.mail;

import br.com.gestorcobranca.identity.application.port.out.IdentityMailer;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Envio de e-mails de identidade via SMTP (compatível com Amazon SES SMTP). O envio ocorre
 * <strong>após o commit</strong> da transação e de forma assíncrona: falha de e-mail nunca
 * desfaz a operação, e e-mail nunca é enviado para operação desfeita. Falhas são registradas
 * no log técnico e na métrica {@code mail.sent{outcome=error}}.
 */
@Component
class SmtpIdentityMailer implements IdentityMailer {

    private static final Logger LOG = LoggerFactory.getLogger(SmtpIdentityMailer.class);
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            .withZone(ZoneId.of("America/Sao_Paulo"));

    private final JavaMailSender mailSender;
    private final TaskExecutor executor;
    private final MeterRegistry meterRegistry;
    private final String from;

    SmtpIdentityMailer(JavaMailSender mailSender, @Qualifier("eventExecutor") TaskExecutor executor,
                       MeterRegistry meterRegistry, @Value("${app.mail.from:no-reply@gestorcobranca.com.br}") String from) {
        this.mailSender = mailSender;
        this.executor = executor;
        this.meterRegistry = meterRegistry;
        this.from = from;
    }

    @Override
    public void sendActivation(String to, String name, String tenantName, String link, Instant expiresAt) {
        String body = """
                <p>Olá, %s.</p>
                <p>Você recebeu acesso ao Gestor de Cobrança da assessoria <strong>%s</strong>.</p>
                <p><a href="%s">Clique aqui para definir sua senha</a>. O link expira em %s.</p>
                <p>Se você não esperava este convite, ignore esta mensagem.</p>
                """.formatted(escape(name), escape(tenantName), escape(link), FORMAT.format(expiresAt));
        sendAfterCommit("activation", to, "Ative seu acesso ao Gestor de Cobrança", body);
    }

    @Override
    public void sendPasswordReset(String to, String name, String link, Instant expiresAt) {
        String body = """
                <p>Olá, %s.</p>
                <p>Recebemos uma solicitação para redefinir sua senha.</p>
                <p><a href="%s">Clique aqui para criar uma nova senha</a>. O link expira em %s.</p>
                <p>Se você não fez esta solicitação, ignore esta mensagem; sua senha atual continua válida.</p>
                """.formatted(escape(name), escape(link), FORMAT.format(expiresAt));
        sendAfterCommit("password_reset", to, "Redefinição de senha — Gestor de Cobrança", body);
    }

    @Override
    public void sendSecurityAlert(String to, String name, String subject, String message) {
        String body = "<p>Olá, %s.</p><p>%s</p>".formatted(escape(name), escape(message));
        sendAfterCommit("security_alert", to, "[Segurança] " + subject, body);
    }

    private void sendAfterCommit(String type, String to, String subject, String html) {
        Runnable send = () -> executor.execute(() -> send(type, to, subject, html));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    private void send(String type, String to, String subject, String html) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            meterRegistry.counter("mail.sent", "type", type, "outcome", "success").increment();
        } catch (MessagingException | MailException e) {
            meterRegistry.counter("mail.sent", "type", type, "outcome", "error").increment();
            LOG.error("Falha ao enviar e-mail de identidade ({}) para {}", type, to, e);
        }
    }

    private static String escape(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }
}
