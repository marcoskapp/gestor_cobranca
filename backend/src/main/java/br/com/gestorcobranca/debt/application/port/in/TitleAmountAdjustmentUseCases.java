package br.com.gestorcobranca.debt.application.port.in;

import br.com.gestorcobranca.debt.application.port.in.TitlePrescriptionUseCases.EvidenceDownload;
import br.com.gestorcobranca.debt.application.port.in.TitlePrescriptionUseCases.EvidenceUpload;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Correção manual do valor do título pela assessoria (decisões 802–813, docs/negocio/correcao-de-valor.md): pedido com
 * motivo, componentes afetados e evidência; aplicado no registro até o limite da assessoria ou depois da aprovação de
 * outra pessoa (RB06); histórico no próprio título; divergência com a carga do credor tratada por alguém.
 * <p>
 * Permissões: {@code TITLE_AMOUNT_ADJUST} (pedir, retirar o próprio pedido, resolver divergências) e
 * {@code TITLE_AMOUNT_APPROVE} (aprovar ou recusar pedidos de outra pessoa e definir o limite). Leitura do histórico com
 * a mesma permissão de leitura do título. Escopo por credor em tudo.
 */
public interface TitleAmountAdjustmentUseCases {

    /** Saldos atuais, limite, se o título aceita correção agora (e por quê não) e o pedido pendente. */
    AdjustmentContext context(UUID titleId);

    /** Pedidos (todas as situações) e divergências do título, do mais recente ao mais antigo. */
    TitleAmountHistory history(UUID titleId);

    /**
     * Pede a correção. Redução até o limite: aplicada já ({@code APPLIED}). Acima do limite ou aumento:
     * {@code PENDING_APPROVAL}, com evidência ou contestação obrigatória.
     *
     * @param evidence arquivo opcional (PDF, PNG ou JPEG, conferido pelo conteúdo)
     */
    AdjustmentView request(UUID titleId, AdjustmentCommand command, EvidenceUpload evidence);

    AdjustmentView approve(UUID adjustmentId, String note);

    AdjustmentView reject(UUID adjustmentId, String note);

    AdjustmentView cancel(UUID adjustmentId, String note);

    /** Fila de aprovação: pedidos pendentes de outras pessoas no escopo. */
    PageResponse<AdjustmentView> pendingApprovals(PageQuery page);

    /** Divergências abertas no escopo. */
    PageResponse<DivergenceView> openDivergences(PageQuery page);

    /**
     * Resolve a divergência: {@code KEEP_MANUAL} mantém a correção (o valor do credor fica reconhecido) ou
     * {@code ACCEPT_CREDITOR} lança no razão o valor informado pelo credor.
     */
    DivergenceView resolveDivergence(UUID divergenceId, String decision, String note);

    EvidenceDownload evidence(UUID adjustmentId);

    SettingsView settings();

    /**
     * Define o limite. Reduzir ou manter vale na hora (e descarta a proposta de aumento em aberto); <b>aumentar</b> fica
     * proposto até outra pessoa com {@code TITLE_AMOUNT_APPROVE} confirmar ({@link #confirmSettings()}).
     */
    SettingsView updateSettings(BigDecimal approvalThreshold);

    /** Confirma o aumento de limite proposto por <b>outra</b> pessoa (409 sem proposta ou para quem propôs). */
    SettingsView confirmSettings();

    /**
     * @param components novo saldo por componente (PRINCIPAL, INTEREST, FINE, FEES, HONORARIUM); os omitidos não mudam
     * @param reason     CALCULATION_ERROR, CREDITOR_CONFIRMATION, DISPUTE_UPHELD, DUPLICATE_CHARGE, CONTRACT_TERMS,
     *                   PARTIAL_WAIVER ou OTHER
     * @param disputeId  contestação que motivou a correção (opcional; referência rastreável, não validada aqui)
     */
    record AdjustmentCommand(Map<String, BigDecimal> components, String reason, String note, UUID disputeId) {

        public AdjustmentCommand {
            components = components == null ? Map.of() : Map.copyOf(components);
        }
    }

    /**
     * @param blockers motivos que impedem a correção agora: {@code STATUS} (título fora de cobrança),
     *                 {@code AGREEMENT} (acordo em vigor — renegociar ou cancelar), {@code PENDING} (pedido aguardando
     *                 aprovação)
     */
    record AdjustmentContext(UUID titleId, List<ComponentAmount> components, BigDecimal principalAmount,
                             BigDecimal openBalance, BigDecimal approvalThreshold, boolean manualPrincipal,
                             BigDecimal creditorPrincipal, List<String> blockers, UUID agreementId,
                             AdjustmentView pending, DivergenceView openDivergence) {
    }

    record ComponentAmount(String component, BigDecimal balance) {
    }

    record AdjustmentLineView(String component, BigDecimal previousBalance, BigDecimal newBalance, BigDecimal delta) {
    }

    /**
     * @param approvalMode WITHIN_LIMIT (aplicada no registro) ou DUAL_CONTROL (segunda pessoa)
     * @param status       PENDING_APPROVAL, APPLIED, REJECTED ou CANCELLED
     */
    record AdjustmentView(UUID id, UUID titleId, UUID creditorId, String status, String approvalMode, String reason,
                          String note, UUID disputeId, EvidenceInfo evidence, BigDecimal previousPrincipal,
                          BigDecimal newPrincipal, BigDecimal previousOpenBalance, BigDecimal newOpenBalance,
                          BigDecimal delta, List<AdjustmentLineView> lines, UUID requestedBy, String requestedByName,
                          Instant requestedAt, UUID decidedBy, String decidedByName, Instant decidedAt,
                          String decisionNote, Instant appliedAt, String titleLabel) {
    }

    record EvidenceInfo(String fileName, String contentType, long size, String sha256) {
    }

    /** @param status OPEN, KEPT_MANUAL, ACCEPTED_CREDITOR, RESOLVED_BY_CREDITOR ou SUPERSEDED */
    record DivergenceView(UUID id, UUID titleId, UUID creditorId, String status, BigDecimal currentPrincipal,
                          BigDecimal reportedPrincipal, UUID importBatchId, Instant detectedAt, Instant lastSeenAt,
                          int occurrences, UUID resolvedBy, String resolvedByName, Instant resolvedAt,
                          String resolutionNote, String titleLabel) {
    }

    record TitleAmountHistory(UUID titleId, List<AdjustmentView> adjustments, List<DivergenceView> divergences) {
    }

    /**
     * @param customized        limite gravado pela assessoria (falso = padrão da plataforma)
     * @param proposedThreshold aumento aguardando a confirmação de outra pessoa (nulo = nenhum)
     * @param proposedByName    retrato do nome de quem propôs
     */
    record SettingsView(BigDecimal approvalThreshold, boolean customized, Instant updatedAt,
                        BigDecimal proposedThreshold, UUID proposedBy, String proposedByName, Instant proposedAt) {
    }
}
