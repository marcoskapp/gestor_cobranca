package br.com.gestorcobranca.compliance.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import br.com.gestorcobranca.shared.config.TimeConfig;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Retenção e descarte (M09, LGPD): o lugar único de "quanto tempo guardamos cada dado" da assessoria. A categoria
 * DEBTOR_INACTIVE é executada aqui (anonimização em lotes de devedores sem títulos em cobrança há N meses, respeitando
 * prazos maiores exigidos pela base legal dos credores); as demais são aplicadas pelos módulos donos dos dados, que
 * consultam o prazo por {@link #retentionFor}.
 * <p>
 * Categorias sem padrão do sistema (DEBTOR_INACTIVE, IMPORT_FILES, MESSAGE_CONTENT, CALL_RECORDINGS; prazo em meses)
 * nascem sem política e nada é descartado até a assessoria configurar e habilitar. Categorias com padrão do sistema
 * (portal e scoring; prazo em dias) descartam sempre: vale o prazo da assessoria, se configurado, ou o padrão
 * informado pelo módulo dono ({@code port.spi.RetentionDefaults}); não podem ser desabilitadas. Exige acesso a todos
 * os credores (exceto {@link #retentionFor}, chamado pelos jobs dos módulos donos).
 */
public interface RetentionUseCases {

    List<RetentionPolicyView> listPolicies();

    /**
     * Cria ou altera a política da categoria.
     *
     * @param retentionAmount prazo na unidade da categoria ({@link RetentionPolicyView#unit()}), dentro dos limites
     * @param enabled         {@code false} só nas categorias sem padrão do sistema
     */
    RetentionPolicyView configure(String category, int retentionAmount, boolean enabled);

    /**
     * "Usar o padrão": apaga a política da assessoria e a categoria volta a descartar pelo padrão do sistema (o prazo
     * passa a acompanhar o padrão, inclusive se ele mudar). Sem política da assessoria, nada muda.
     * Erros: categoria sem padrão do sistema → {@code retention.no_system_default} (422).
     */
    RetentionPolicyView resetToDefault(String category);

    /**
     * Para os jobs dos módulos donos (no tenant do contexto): prazo a aplicar agora no descarte da categoria — o da
     * assessoria (política configurada e habilitada) ou o padrão do sistema. Vazio = nada a descartar (categoria sem
     * padrão e sem política habilitada).
     */
    Optional<AppliedRetention> retentionFor(String category);

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

    /**
     * Política de uma categoria. A lista traz todas as categorias: as ainda não configuradas vêm com
     * {@code configured=false} e sem prazo da assessoria, para serem configuradas na própria tela.
     *
     * @param unit            DAYS ou MONTHS (fixa por categoria)
     * @param retentionAmount prazo configurado pela assessoria (nulo se não configurada)
     * @param defaultAmount   padrão do sistema (nulo na categoria sem padrão)
     * @param minAmount       menor prazo aceito
     * @param maxAmount       maior prazo aceito
     * @param enabled         descarte ativo (sempre, na categoria com padrão do sistema)
     * @param executableHere  executada pelo módulo compliance (as demais são aplicadas pelo módulo dono)
     */
    record RetentionPolicyView(String category, String action, String unit, Integer retentionAmount,
                               Integer defaultAmount, int minAmount, int maxAmount, boolean enabled,
                               boolean executableHere, boolean configured, Instant updatedAt) {
    }

    /**
     * Prazo a aplicar no descarte.
     *
     * @param unit   DAYS ou MONTHS
     * @param source {@code TENANT} (política da assessoria) ou {@code SYSTEM_DEFAULT} (padrão do sistema)
     */
    record AppliedRetention(String category, int amount, String unit, String source) {

        public static final String TENANT = "TENANT";
        public static final String SYSTEM_DEFAULT = "SYSTEM_DEFAULT";

        /** Instante de corte: o que é anterior a ele (pela referência da categoria) é descartado. */
        public Instant cutoff(Instant now) {
            ZonedDateTime at = ZonedDateTime.ofInstant(now, TimeConfig.BUSINESS_ZONE);
            return ("DAYS".equals(unit) ? at.minusDays(amount) : at.minusMonths(amount)).toInstant();
        }

        /** Prazo legível, para trilha e motivo de descarte (ex.: "30 dias", "6 meses"). */
        public String describe() {
            return amount + ("DAYS".equals(unit) ? (amount == 1 ? " dia" : " dias") : (amount == 1 ? " mês" : " meses"));
        }
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
