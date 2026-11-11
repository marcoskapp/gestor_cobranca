package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.assistant.application.port.out.AssistantDataSharingQueries;
import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort;
import br.com.gestorcobranca.shared.privacy.DataRecipientType;
import br.com.gestorcobranca.shared.privacy.DataSharingFact;
import br.com.gestorcobranca.shared.privacy.DataSharingLink;
import br.com.gestorcobranca.shared.privacy.DataSharingPurpose;
import br.com.gestorcobranca.shared.privacy.DataSharingQuery;
import br.com.gestorcobranca.shared.privacy.DataSharingRecipient;
import br.com.gestorcobranca.shared.privacy.DataSharingSource;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Conversas do assistente virtual com o devedor em que o provedor de IA recebeu o contexto minimizado (turnos com
 * chamada ao modelo). O link abre o turno mais recente na trilha do assistente. O provedor em uso vem da configuração
 * da instância (não há tela de provedor).
 */
@Component
class AssistantDataSharing implements DataSharingSource {

    private static final Map<String, String> NAMES = Map.of(
            "bedrock", "Amazon Bedrock (modelos de IA)",
            "sandbox", "Assistente de demonstração (sandbox)");

    private final AssistantDataSharingQueries queries;
    private final ObjectProvider<LanguageModelPort> models;

    AssistantDataSharing(AssistantDataSharingQueries queries, ObjectProvider<LanguageModelPort> models) {
        this.queries = queries;
        this.models = models;
    }

    @Override
    public String id() {
        return "assistant";
    }

    @Override
    public String label() {
        return "Assistente virtual";
    }

    @Override
    public List<DataSharingFact> sharedWith(DataSharingQuery query) {
        return queries.turnsByProvider(query.debtorId(), query.creditorScope()).stream()
                .map(u -> new DataSharingFact(DataRecipientType.PROVIDER, key(u.code()), null, name(u.code()),
                        DataSharingPurpose.VIRTUAL_ASSISTANT, u.firstAt(), u.lastAt(), u.count(),
                        DataSharingFact.counted(u.count(), "resposta do assistente", "respostas do assistente"),
                        u.latestRecordId() == null ? null
                                : DataSharingLink.to("/assistente/turnos/" + u.latestRecordId(),
                                        "Abrir a resposta mais recente", Permission.ASSISTANT_REVIEW)))
                .toList();
    }

    /**
     * O provedor de IA da instância só está "em uso" na assessoria que tem política do assistente publicada e ligada:
     * sem ela, nenhum dado vai ao modelo e o cadastro de operador não é necessário.
     */
    @Override
    public List<DataSharingRecipient> recipientsInUse() {
        LanguageModelPort model = models.getIfAvailable();
        return model == null || !queries.hasActivePublishedPolicy() ? List.of()
                : List.of(new DataSharingRecipient(key(model.provider()), name(model.provider()),
                        DataSharingPurpose.VIRTUAL_ASSISTANT));
    }

    static String key(String provider) {
        return "assistant." + provider.toLowerCase(Locale.ROOT).replaceAll("[^a-z_]", "_");
    }

    private static String name(String provider) {
        return NAMES.getOrDefault(provider.toLowerCase(Locale.ROOT), provider);
    }
}
