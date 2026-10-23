package br.com.gestorcobranca.communication.infrastructure.provider.email;

import br.com.gestorcobranca.communication.application.port.out.ChannelGateway.OutboundRequest;
import br.com.gestorcobranca.communication.application.port.out.ChannelGateway.SendResult;
import br.com.gestorcobranca.communication.application.port.out.ChannelGateway.TransientSendException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmtpEmailGatewayTest {

    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final SmtpEmailGateway gateway = new SmtpEmailGateway(sender, new SimpleMeterRegistry());
    private final UUID messageId = UUID.randomUUID();

    private OutboundRequest request() {
        return new OutboundRequest(UUID.randomUUID(), Map.of(), "cobranca@credor-a.com.br", "Banco A",
                "atendimento@credor-a.com.br", "devedor@exemplo.com", "Aviso de débito", "Olá", null, messageId);
    }

    @Test
    void sendsWithCreditorSenderAndReplyTo() throws Exception {
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        SendResult result = gateway.send(request());
        assertThat(result.accepted()).isTrue();
        assertThat(result.providerMessageId()).isEqualTo("smtp-" + messageId);
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        MimeMessage sent = captor.getValue();
        assertThat(sent.getFrom()[0].toString()).contains("cobranca@credor-a.com.br").contains("Banco A");
        assertThat(sent.getReplyTo()[0].toString()).isEqualTo("atendimento@credor-a.com.br");
        assertThat(sent.getSubject()).isEqualTo("Aviso de débito");
        assertThat(sent.getHeader("X-Gestor-Message-Id")[0]).isEqualTo(messageId.toString());
    }

    @Test
    void authenticationErrorIsDefinitiveAndSendFailureIsTransient() {
        when(sender.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailAuthenticationException("auth")).when(sender).send(any(MimeMessage.class));
        assertThat(gateway.send(request()).accepted()).isFalse();
        doThrow(new MailSendException("down")).when(sender).send(any(MimeMessage.class));
        assertThatThrownBy(() -> gateway.send(request())).isInstanceOf(TransientSendException.class);
    }
}
