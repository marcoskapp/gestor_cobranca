package br.com.gestorcobranca.communication.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Templates de mensagem versionados por credor (nulo = assessoria), com variáveis tipadas
 * ({@code {{nome}}}) — tipos sem documento, contato ou endereço, e textos livres verificados
 * contra esses dados. Um rascunho é editável; publicar torna a versão vigente do código (a anterior
 * é retirada); alterar uma versão publicada = criar a próxima versão.
 * <p>
 * WhatsApp: publicar exige a aprovação do provedor registrada ({@link #registerApproval}); editar
 * o rascunho volta a aprovação para pendente. Escopo como nas contas de canal; auditado.
 */
public interface TemplateUseCases {

    /** Cria a próxima versão (rascunho) do código para o credor (1 se o código é novo). */
    TemplateView createDraft(CreateTemplateCommand command);

    TemplateView updateDraft(UUID templateId, TemplateContent content);

    /** Registra a decisão do provedor (WhatsApp): PENDING (submetido), APPROVED ou REJECTED. */
    TemplateView registerApproval(UUID templateId, ApprovalCommand command);

    TemplateView publish(UUID templateId);

    TemplateView retire(UUID templateId);

    TemplateView get(UUID templateId);

    PageResponse<TemplateView> search(TemplateFilter filter, PageQuery page);

    /** Renderiza com valores de exemplo, aplicando as mesmas validações do envio. */
    Preview preview(UUID templateId, Map<String, String> values);

    /** @param type FIRST_NAME, MONEY, DATE, INTEGER, URL, CODE ou TEXT */
    record VariableInput(String name, String type, boolean required, Integer maxLength) {
    }

    /**
     * @param purpose COLLECTION, REMINDER, NOTIFICATION, AGREEMENT, PAYMENT ou SERVICE
     * @param subject obrigatório no e-mail; proibido nos demais canais
     * @param language idioma no provedor (padrão {@code pt_BR})
     * @param providerTemplateName nome do template no provedor (WhatsApp)
     */
    record TemplateContent(String name, String purpose, String subject, String body, List<VariableInput> variables,
                           String language, String providerTemplateName) {
    }

    /** @param channel WHATSAPP, EMAIL ou SMS */
    record CreateTemplateCommand(UUID creditorId, String code, String channel, TemplateContent content) {
    }

    /** @param decision PENDING, APPROVED ou REJECTED; @param decidedAt nulo = agora */
    record ApprovalCommand(String decision, String providerTemplateName, String reference, String note,
                           Instant decidedAt) {
    }

    /** Filtros opcionais; {@code status} DRAFT, PUBLISHED ou RETIRED. */
    record TemplateFilter(UUID creditorId, String code, String channel, String status) {
    }

    record TemplateView(UUID id, UUID creditorId, String code, int templateVersion, String channel, String name,
                        String purpose, String subject, String body, List<VariableInput> variables, String language,
                        String status, String providerTemplateName, String approvalStatus, String approvalReference,
                        String approvalNote, Instant approvalDecidedAt, UUID approvalRegisteredBy, Instant publishedAt,
                        UUID publishedBy, Instant retiredAt, Instant createdAt, long version) {
    }

    record Preview(String subject, String body, List<String> parameters) {
    }
}
