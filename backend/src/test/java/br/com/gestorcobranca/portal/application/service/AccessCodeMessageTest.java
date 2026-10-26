package br.com.gestorcobranca.portal.application.service;

import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.TemplateView;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.VariableInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** O código vai sempre; a validade só se o template a declara (template de autenticação da Meta não declara). */
class AccessCodeMessageTest {

    private static final UUID CREDITOR = UUID.randomUUID();

    private final TemplateUseCases templates = mock(TemplateUseCases.class);
    private final AccessCodeMessage message = new AccessCodeMessage(templates);

    private static TemplateView template(String category, VariableInput... variables) {
        return new TemplateView(UUID.randomUUID(), null, "PORTAL_CODIGO_WHATSAPP", 1, "WHATSAPP", "Código", "SERVICE",
                null, "{{codigo}}", List.of(variables), "pt_BR", "PUBLISHED", "portal_codigo", category, "APPROVED",
                null, null, null, null, null, null, null, null, 0);
    }

    @Test
    void authenticationTemplateReceivesOnlyTheCode() {
        when(templates.findEffective(CREDITOR, "PORTAL_CODIGO_WHATSAPP")).thenReturn(Optional.of(template(
                "AUTHENTICATION", new VariableInput("codigo", "CODE", true, 15))));
        assertThat(message.variables(CREDITOR, "PORTAL_CODIGO_WHATSAPP", "123456", 10))
                .containsExactlyEntriesOf(Map.of("codigo", "123456"));
    }

    @Test
    void templateWithValidityReceivesBoth() {
        when(templates.findEffective(CREDITOR, "PORTAL_CODIGO_SMS")).thenReturn(Optional.of(template(null,
                new VariableInput("codigo", "CODE", true, null), new VariableInput("minutos", "INTEGER", true, null))));
        assertThat(message.variables(CREDITOR, "PORTAL_CODIGO_SMS", "123456", 10))
                .containsEntry("codigo", "123456").containsEntry("minutos", "10").hasSize(2);
    }

    @Test
    void withoutPublishedTemplateKeepsBothAndCommunicationRefusesTheSend() {
        when(templates.findEffective(CREDITOR, "PORTAL_CODIGO_EMAIL")).thenReturn(Optional.empty());
        assertThat(message.variables(CREDITOR, "PORTAL_CODIGO_EMAIL", "123456", 10)).hasSize(2);
    }
}
