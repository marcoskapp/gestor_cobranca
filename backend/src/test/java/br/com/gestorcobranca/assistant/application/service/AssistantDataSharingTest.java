package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.assistant.application.port.out.AssistantDataSharingQueries;
import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort;
import br.com.gestorcobranca.shared.privacy.DataSharingRecipient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * O provedor de IA da instância só aparece entre os "provedores em uso" quando a assessoria tem política do assistente
 * publicada e ligada: sem ela nenhum dado vai ao modelo.
 */
class AssistantDataSharingTest {

    private final AssistantDataSharingQueries queries = mock(AssistantDataSharingQueries.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<LanguageModelPort> models = mock(ObjectProvider.class);
    private final LanguageModelPort model = mock(LanguageModelPort.class);
    private final AssistantDataSharing sharing = new AssistantDataSharing(queries, models);

    @Test
    void listsTheModelProviderOnlyWithAnActivePublishedPolicy() {
        when(models.getIfAvailable()).thenReturn(model);
        when(model.provider()).thenReturn("bedrock");

        when(queries.hasActivePublishedPolicy()).thenReturn(false);
        assertThat(sharing.recipientsInUse()).isEmpty();

        when(queries.hasActivePublishedPolicy()).thenReturn(true);
        assertThat(sharing.recipientsInUse()).extracting(DataSharingRecipient::recipientKey)
                .containsExactly("assistant.bedrock");
    }

    @Test
    void withoutModelNothingIsInUse() {
        when(queries.hasActivePublishedPolicy()).thenReturn(true);
        assertThat(sharing.recipientsInUse()).isEmpty();
    }
}
