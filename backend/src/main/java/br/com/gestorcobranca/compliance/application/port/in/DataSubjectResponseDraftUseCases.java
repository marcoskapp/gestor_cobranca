package br.com.gestorcobranca.compliance.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Rascunho da resposta ao titular para os pedidos de confirmação de tratamento (art. 18, I) e de informação sobre
 * compartilhamento (art. 18, VII): o sistema reúne o que registra (credores e bases legais, prestadores e o cadastro
 * de operadores, escritórios parceiros e integrações) e sugere o texto; o analista confere, edita e só então conclui
 * a solicitação — nada é enviado sozinho.
 * <p>
 * Exige o mesmo alcance de atuar na solicitação (todos os credores) e a identidade do titular confirmada; a solicitação
 * precisa estar aberta. Cada geração registra {@code SENSITIVE_DATA_VIEWED} no log de acesso e
 * {@code data_subject_request.response_draft_generated} na auditoria da solicitação.
 */
public interface DataSubjectResponseDraftUseCases {

    /**
     * Erros: tipo sem rascunho → {@code dsr.draft_unavailable} (422); identidade não confirmada →
     * {@code dsr.identity_not_verified} (409); solicitação encerrada → {@code dsr.draft_closed} (409).
     */
    ResponseDraftView draft(UUID requestId);

    /**
     * @param processing     há tratamento de dados do titular (titular localizado na base)
     * @param dataCategories categorias de dados tratadas, em linguagem simples
     * @param unavailable    áreas que não puderam ser consultadas (rascunho possivelmente incompleto)
     * @param text           texto sugerido (parágrafos separados por linha em branco)
     * @param textSha256     resumo do texto sugerido: informado na conclusão, registra se a resposta saiu do rascunho
     *                       e se foi editada
     */
    record ResponseDraftView(UUID requestId, String type, Instant generatedAt, boolean processing,
                             List<String> dataCategories, List<RecipientView> recipients, List<AlertView> alerts,
                             List<String> unavailable, List<ParagraphView> paragraphs, String text, String textSha256,
                             int textMaxLength) {
    }

    /**
     * Destinatário agregado (fatos da mesma chave somados).
     *
     * @param type          CREDITOR, PROVIDER, LAW_FIRM ou INTEGRATION
     * @param key           chave estável do destinatário (a do cadastro de operadores)
     * @param purposes      finalidades, em linguagem simples
     * @param countLabel    quantidades por extenso ("12 mensagens")
     * @param links         telas que abrem a evidência (o front mostra só as que a pessoa abre)
     * @param legalBases    bases legais vigentes (credor), em linguagem simples
     * @param processor     cadastro do operador (prestador; nulo = sem cadastro)
     */
    record RecipientView(String type, String key, UUID recipientId, String name, List<String> purposes,
                         Instant firstAt, Instant lastAt, long count, String countLabel, List<LinkView> links,
                         List<String> legalBases, ProcessorSummary processor) {
    }

    /** Resumo do cadastro do operador que casa com a chave. */
    record ProcessorSummary(UUID id, String name, String role, String purpose, String countryCode, String countryName,
                            boolean internationalTransfer, String transferSafeguard, String contractReference,
                            boolean current) {
    }

    /** Link para a tela da evidência, com as permissões da rota (uma de {@code anyPermission} e todas de {@code allPermissions}). */
    record LinkView(String route, Map<String, String> queryParams, Set<String> anyPermission, Set<String> allPermissions,
                    String label) {
    }

    /**
     * @param code         PROCESSOR_UNREGISTERED, PROCESSOR_NOT_CURRENT, CREDITOR_WITHOUT_LEGAL_BASIS,
     *                     SOURCE_UNAVAILABLE ou TEXT_TOO_LONG
     * @param severity     WARNING ou DANGER
     * @param recipientKey destinatário do alerta (nulo para alertas gerais)
     * @param recipientId  id do registro do destinatário (credor, conta), quando houver
     */
    record AlertView(String code, String severity, String message, String recipientKey, UUID recipientId) {
    }

    /** Parágrafo do texto e os destinatários de onde veio (explicabilidade). */
    record ParagraphView(String text, List<String> recipientKeys) {
    }
}
