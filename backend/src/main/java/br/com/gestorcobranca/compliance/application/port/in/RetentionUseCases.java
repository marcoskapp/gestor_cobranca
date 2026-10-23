package br.com.gestorcobranca.compliance.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Retenção e descarte (M09, LGPD). Políticas por categoria nascem desabilitadas. A categoria
 * DEBTOR_INACTIVE é executada aqui (anonimização em lotes de devedores sem títulos em cobrança
 * há N meses, respeitando prazos maiores exigidos pela base legal dos credores); IMPORT_FILES,
 * MESSAGE_CONTENT e CALL_RECORDINGS são políticas declaradas que os módulos donos dos dados
 * consultam por {@link #policyFor}. Exige acesso a todos os credores.
 */
public interface RetentionUseCases {

    List<RetentionPolicyView> listPolicies();

    /** Cria ou altera a política da categoria. */
    RetentionPolicyView configure(String category, int retentionMonths, boolean enabled);

    /** Para outros módulos: política da categoria, se configurada. */
    Optional<RetentionPolicyView> policyFor(String category);

    /**
     * Executa agora a categoria DEBTOR_INACTIVE. {@code dryRun} apenas relaciona os devedores
     * elegíveis (relatório), sem anonimizar; a execução efetiva exige a política habilitada e
     * também a permissão de atendimento a titulares (anonimização).
     */
    RetentionRunView run(String category, boolean dryRun);

    PageResponse<RetentionRunView> listRuns(PageQuery page);

    RetentionRunView getRun(UUID runId);

    /** Itens da execução: o relatório da simulação ou a evidência do descarte. */
    PageResponse<RetentionRunItemView> runItems(UUID runId, PageQuery page);

    /** @param executableHere executada pelo módulo compliance (as demais são declaradas) */
    record RetentionPolicyView(String category, int retentionMonths, String action, boolean enabled,
                               boolean executableHere, Instant updatedAt) {
    }

    /**
     * @param candidateCount devedores sem movimentação desde o corte
     * @param eligibleCount  candidatos não retidos por base legal
     * @param processedCount anonimizados (zero em simulação)
     * @param retainedCount  mantidos por prazo maior da base legal
     */
    record RetentionRunView(UUID id, String category, String trigger, boolean dryRun, int retentionMonths,
                            Instant cutoff, Instant startedAt, Instant finishedAt, String status, int candidateCount,
                            int eligibleCount, int processedCount, int retainedCount, int failedCount,
                            String failureReason) {
    }

    /** @param outcome ELIGIBLE (simulação), ANONYMIZED, RETAINED ou FAILED */
    record RetentionRunItemView(UUID debtorId, String outcome, String errorCode, String detail, Instant processedAt) {
    }
}
