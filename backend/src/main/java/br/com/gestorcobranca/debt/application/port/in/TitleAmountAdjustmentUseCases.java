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
     * Pede a correção. Redução pura até o limite, somada às reduções aplicadas sem segunda pessoa no mesmo título na
     * janela do limite acumulado: aplicada já ({@code APPLIED}). Acima do limite (sozinha ou somada), ou aumento:
     * {@code PENDING_APPROVAL}, com evidência ou contestação obrigatória. Sem transação longa: o arquivo é gravado fora de
     * transação e removido se o registro falhar ou a regra recusar o pedido.
     *
     * @param evidence arquivo opcional (PDF, PNG ou JPEG, conferido pelo conteúdo)
     */
    AdjustmentView request(UUID titleId, AdjustmentCommand command, EvidenceUpload evidence);

    AdjustmentView approve(UUID adjustmentId, String note);

    AdjustmentView reject(UUID adjustmentId, String note);

    AdjustmentView cancel(UUID adjustmentId, String note);

    /** Fila de aprovação: pedidos pendentes de outras pessoas no escopo. */
    PageResponse<AdjustmentView> pendingApprovals(PendingFilter filter, PageQuery page);

    /**
     * Pedido para a tela de aprovação: o pedido e quanto ele passa do limite de quem corrige. {@code TITLE_AMOUNT_APPROVE}
     * e credor no escopo; 422 {@code title_amount.not_dual_control} para pedido aplicado dentro do limite.
     */
    ApprovalView approval(UUID adjustmentId);

    /** @param creditorId só deste credor (nulo = todos os do escopo) */
    record PendingFilter(UUID creditorId) {

        public static final PendingFilter ALL = new PendingFilter(null);
    }

    /**
     * @param reason           INCREASE (algum componente aumenta: sempre segunda pessoa), REDUCTION_ABOVE_LIMIT (a
     *                         redução sozinha passa do limite) ou CUMULATIVE_ABOVE_LIMIT (somada às reduções recentes)
     * @param increases        soma dos aumentos de componente
     * @param decreases        soma das reduções de componente (não o líquido)
     * @param recentReductions reduções aplicadas sem segunda pessoa no título na janela, no pedido
     * @param threshold        limite da assessoria no pedido (pedido antigo: o limite atual)
     * @param excess           quanto as reduções, com as recentes, passam do limite (zero se o motivo é só aumento)
     * @param ownRequest       pedido de quem consulta (segregação: não pode decidir)
     */
    record ApprovalView(AdjustmentView adjustment, String reason, BigDecimal increases, BigDecimal decreases,
                        BigDecimal recentReductions, BigDecimal threshold, BigDecimal excess, long cumulativeWindowDays,
                        boolean ownRequest) {
    }

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
     * proposto até outra pessoa com {@code TITLE_AMOUNT_APPROVE} confirmar ({@link #confirmSettings(BigDecimal)}).
     */
    SettingsView updateSettings(BigDecimal approvalThreshold);

    /**
     * Confirma o aumento de limite proposto por <b>outra</b> pessoa. {@code reviewedThreshold} é o valor proposto que
     * quem confirma viu; diferente do gravado → 409 {@code title_amount.proposal_changed}. Proposta vencida (prazo
     * {@code app.debt.amount-adjustment.proposal-validity}) → 409 {@code title_amount.proposal_expired}. Também 409 sem
     * proposta ou para quem propôs.
     */
    SettingsView confirmSettings(BigDecimal reviewedThreshold);

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
     * @param recentReductions     soma das reduções aplicadas sem segunda pessoa neste título na janela do limite
     *                             acumulado (somam com a correção nova para a prévia da alçada)
     * @param cumulativeWindowDays janela do limite acumulado, em dias
     */
    record AdjustmentContext(UUID titleId, List<ComponentAmount> components, BigDecimal principalAmount,
                             BigDecimal openBalance, BigDecimal approvalThreshold, boolean manualPrincipal,
                             BigDecimal creditorPrincipal, List<String> blockers, UUID agreementId,
                             AdjustmentView pending, DivergenceView openDivergence, BigDecimal recentReductions,
                             long cumulativeWindowDays) {
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
     * @param proposalExpiresAt fim do prazo para confirmar a proposta (nulo sem proposta)
     * @param proposalExpired   a proposta venceu e não pode mais ser confirmada (o job diário a descarta)
     * @param cumulativeWindowDays janela do limite acumulado por título, em dias
     */
    record SettingsView(BigDecimal approvalThreshold, boolean customized, Instant updatedAt,
                        BigDecimal proposedThreshold, UUID proposedBy, String proposedByName, Instant proposedAt,
                        Instant proposalExpiresAt, boolean proposalExpired, long cumulativeWindowDays) {
    }
}
