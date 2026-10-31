package br.com.gestorcobranca.ingestion.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Importação PDF inteligente ({@code docs/tecnico/desenho/importacao-pdf.md}): uma <strong>nova fonte</strong> da
 * importação de carteiras. O PDF é lido, a IA propõe (ou o mapa salvo do credor fornece) o mapa de extração, o
 * intérprete determinístico aplica o mapa ao texto e o usuário valida o "mapa" numa tela intermediária. A aprovação gera
 * o lote no formato canônico e abre a conferência do ingestion (formato, linhas, grandes totais, alertas, efetivação)
 * — nada é gravado na carteira antes disso, e nada é importado sem aprovação humana.
 * <p>
 * Permissões: leitura {@code IMPORT_VIEW}; envio, mapa, decisões, aprovação e descarte {@code IMPORT_EXECUTE}; valores
 * sem máscara e imagens das páginas exigem {@code IMPORT_EXECUTE} ou {@code DEBTOR_SENSITIVE_VIEW} e são registrados
 * no log de acesso; arquivo original exige {@code DEBTOR_SENSITIVE_VIEW}. Escopo por credor em tudo.
 */
public interface PdfImportUseCases {

    /** Recebe o PDF (tela ou API pública), confere e guarda o arquivo e agenda o mapeamento. */
    PdfImportView upload(UploadPdfCommand command);

    PdfImportView get(UUID id);

    PageResponse<PdfImportSummaryView> search(PdfImportSearch filter, PageQuery page);

    /** Títulos encontrados (filtrados), com a origem no PDF e as ocorrências de cada um. */
    PageResponse<PdfRecordView> records(UUID id, RecordFilter filter, PageQuery page);

    /** Contadores por situação (com o devedor e o texto do filtro) e os devedores, para os filtros da tela. */
    RecordFacets recordFacets(UUID id, RecordFilter filter);

    /** Imagem PNG de uma página (visualização na tela de validação). */
    PageImage page(UUID id, int page);

    /** Troca o mapa (edição do usuário) e reprocessa de forma determinística. */
    PdfImportView updateMap(UUID id, UpdateMapCommand command);

    /** Pede nova proposta de mapa à IA (layout que não casou com o mapa salvo, ou proposta ruim). */
    PdfImportView requestNewMap(UUID id);

    /**
     * Exclui do lote, reinclui ou confirma (leitura visual) títulos antes da aprovação — um título ou em massa (lista de
     * números ou o critério do filtro), numa única transação. Aplica o permitido e informa o que ficou de fora; título
     * de confiança baixa só é confirmado um a um (decisão 667). Auditoria: uma entrada por título, com o motivo e o
     * identificador da operação.
     */
    RecordDecisionResult decideRecords(UUID id, RecordDecisionCommand command);

    /** "Concordo, importar": gera o lote canônico e abre a conferência da importação. */
    PdfImportView approve(UUID id, ApproveCommand command);

    /** "Salvar mapa para este credor". */
    PdfTemplateView saveTemplate(UUID id, SaveTemplateCommand command);

    PdfImportView discard(UUID id, String reason);

    List<PdfTemplateView> templates(UUID creditorId, boolean includeInactive);

    PdfTemplateView deactivateTemplate(UUID templateId);

    /** Arquivo original (dados pessoais em claro): {@code DEBTOR_SENSITIVE_VIEW} e {@code FILE_DOWNLOADED}. */
    ImportBatchUseCases.DownloadableFile originalFile(UUID id);

    // ------------------------------------------------------------------ comandos

    /**
     * @param channel        SCREEN ou PUBLIC_API
     * @param idempotencyKey chave da integração (API pública); repetir devolve a mesma importação
     */
    record UploadPdfCommand(UUID portfolioId, String fileName, String contentType, long size,
                            ImportBatchUseCases.FileContent content, String channel, String idempotencyKey) {
    }

    /** @param map mapa de extração (objeto JSON); {@code revision} = revisão que o usuário editou (concorrência) */
    record UpdateMapCommand(Map<String, Object> map, int revision) {
    }

    /**
     * Filtro dos títulos.
     *
     * @param status ALL, OK, WARNING, ERROR, LOW_CONFIDENCE (leitura visual com confiança baixa ainda não confirmada) ou
     *               EXCLUDED; OK, WARNING e ERROR não incluem os excluídos
     * @param debtor índice do devedor no documento (1, 2…); nulo = todos
     * @param query  texto livre: identificador do título e contrato e, para quem vê dado pessoal, nome e CPF/CNPJ
     */
    record RecordFilter(String status, Integer debtor, String query) {

        public static RecordFilter all() {
            return new RecordFilter(null, null, null);
        }
    }

    /**
     * Decisão sobre títulos: pelos números ({@code ordinals}) <strong>ou</strong> pelo critério do filtro
     * ({@code filter}, todos os títulos que o satisfazem na revisão informada).
     *
     * @param ordinals      títulos (posição no documento)
     * @param filter        critério do filtro (seleção "todos os N do filtro")
     * @param expectedCount com {@code filter}: quantos títulos o usuário viu no filtro (outro número = 409)
     * @param decision      EXCLUDE, INCLUDE ou CONFIRM (leitura visual)
     * @param justification obrigatória para EXCLUDE e CONFIRM (motivo único do grupo)
     * @param revision      revisão do mapa que o usuário está vendo (concorrência)
     */
    record RecordDecisionCommand(List<Integer> ordinals, RecordFilter filter, Integer expectedCount, String decision,
                                 String justification, int revision) {

        /** Decisão sobre títulos escolhidos pelo número. */
        public RecordDecisionCommand(List<Integer> ordinals, String decision, String justification, int revision) {
            this(ordinals, null, null, decision, justification, revision);
        }
    }

    /**
     * @param totalsJustification obrigatória quando os totais do documento não conferem
     * @param visualReadingConfirmed obrigatória na leitura visual: conferi os valores com as imagens
     * @param revision            revisão do mapa que o usuário está aprovando
     */
    record ApproveCommand(int revision, String totalsJustification, boolean visualReadingConfirmed) {
    }

    record SaveTemplateCommand(String name, int revision) {
    }

    /** @param status vários separados por vírgula */
    record PdfImportSearch(UUID creditorId, UUID portfolioId, String status) {
    }

    // ------------------------------------------------------------------ visões

    record PageImage(int page, String contentType, byte[] content) {
    }

    /**
     * @param operationId identificador da operação (na auditoria de cada título)
     * @param bulk        decisão em massa (mais de um título ou pelo filtro)
     * @param requested   títulos alcançados pela seleção
     * @param applied     títulos em que a decisão foi aplicada
     * @param skipped     o que ficou de fora, por motivo
     */
    record RecordDecisionResult(PdfImportView importView, UUID operationId, String decision, boolean bulk,
                                int requested, int applied, List<SkippedRecords> skipped) {
    }

    /** @param code already_excluded, not_excluded, not_visual, already_confirmed ou low_confidence_one_by_one */
    record SkippedRecords(String code, String message, List<Integer> ordinals) {
    }

    /**
     * @param byStatus quantidade por situação (ALL, OK, WARNING, ERROR, LOW_CONFIDENCE, EXCLUDED), já com o devedor e
     *                 o texto do filtro aplicados
     * @param debtors  devedores do documento (nome mascarado para quem não vê dado pessoal)
     */
    record RecordFacets(Map<String, Integer> byStatus, List<DebtorFacet> debtors) {
    }

    record DebtorFacet(int index, String name, int records) {
    }

    record PdfImportSummaryView(UUID id, UUID creditorId, UUID portfolioId, String channel, String status,
                                String fileName, int pages, boolean textLayer, String mapOrigin, int records,
                                boolean totalsMatch, UUID reviewId, UUID createdBy, Instant createdAt) {
    }

    /**
     * Estado completo da importação PDF (sem valores do devedor).
     *
     * @param map           mapa corrente (objeto JSON)
     * @param mapProblems   problemas do mapa (impedem a aplicação)
     * @param explanation   o mapa em linguagem simples ("Coluna 'Pendente' → saldo")
     * @param blockers      motivos que impedem a aprovação agora
     * @param canonicalFields campos do layout canônico (para o editor do mapa)
     */
    record PdfImportView(UUID id, UUID creditorId, UUID portfolioId, String channel, String status, String fileName,
                         long fileSize, String fileSha256, int pages, boolean textLayer, boolean fileAvailable,
                         String mapOrigin, int mapRevision, UUID templateId, String templateName,
                         Map<String, Object> map, List<MapProblemView> mapProblems, List<String> explanation,
                         SummaryView summary, List<TotalsView> totals, List<FindingView> findings,
                         List<PageView> pageSizes, List<RegionView> regions, UsageView usage, String failureCode,
                         String failureMessage, List<String> blockers, boolean totalsJustificationRequired,
                         boolean visualConfirmationRequired, UUID reviewId, UUID approvedBy, Instant approvedAt,
                         String totalsJustification, UUID discardedBy, Instant discardedAt, String discardReason,
                         List<RevisionView> revisions, List<CanonicalFieldOption> canonicalFields, UUID createdBy,
                         Instant createdAt) {
    }

    record MapProblemView(String code, String path, String message) {
    }

    /**
     * @param records        títulos encontrados
     * @param recordsToImport sem os excluídos
     * @param debtors        devedores (blocos) encontrados
     * @param principalTotal soma de {@code valor_principal} dos títulos a importar
     * @param balanceTotal   soma de {@code saldo} dos títulos a importar
     */
    record SummaryView(int records, int recordsToImport, int excluded, int debtors, int errors, int warnings,
                       int recordsWithError, int unrecognizedLines, int lowConfidence, int confirmed,
                       BigDecimal principalTotal, BigDecimal balanceTotal, boolean totalsMatch, boolean blocking) {
    }

    /** @param scope DOCUMENT ou o índice do devedor ("1", "2"…) */
    record TotalsView(String scope, int debtorIndex, String label, String column, String target, BigDecimal document,
                      BigDecimal extracted, BigDecimal difference, boolean matches, long records, RegionView source) {
    }

    record FindingView(String severity, String code, String message, Integer record, String field, boolean blocking,
                       RegionView source) {
    }

    record PageView(int page, double width, double height) {
    }

    /** @param kind HEADER (cabeçalho da tabela), DEBTOR, TOTAL, DOCUMENT_FIELD, UNRECOGNIZED */
    record RegionView(String kind, int page, double x0, double top, double x1, double bottom) {
    }

    record UsageView(int calls, long inputTokens, long outputTokens, BigDecimal cost, String currency, String model,
                     long latencyMs) {
    }

    record RevisionView(int revision, String origin, int records, boolean totalsMatch, boolean blocking,
                        UUID createdBy, Instant createdAt) {
    }

    record CanonicalFieldOption(String field, boolean required, boolean personal) {
    }

    /**
     * @param values   campo canônico → valor (mascarado para quem não pode ver dado pessoal)
     * @param status   OK, WARNING ou ERROR
     * @param decision EXCLUDED, CONFIRMED ou nulo
     */
    record PdfRecordView(int ordinal, int debtorIndex, String origin, Double confidence, Map<String, String> values,
                         Map<String, String> info, Map<String, List<RegionView>> sources, List<RegionView> lines,
                         Integer page, String status, List<FindingView> findings, String decision, boolean masked) {
    }

    record PdfTemplateView(UUID id, UUID creditorId, String name, String fingerprint, int version, boolean active,
                           int uses, Instant lastUsedAt, UUID sourceImportId, Map<String, Object> map,
                           UUID createdBy, Instant createdAt) {
    }
}
