package br.com.gestorcobranca.legal.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Casos jurídicos: indicação manual, consulta, ficha, linha do tempo, transições de fase,
 * responsável (jurídico interno ou escritório parceiro) e painel.
 * <p>
 * Escopo: credor do caso no escopo do ator (404 na leitura, 403 nos comandos); membros de
 * escritório parceiro só alcançam os casos atribuídos ao seu escritório (404 fora deles). Toda
 * mudança é auditada, gera evento de negócio {@code legal.*} e, nas fases, o evento de integração
 * {@code LegalMatterStageChanged}. A entrada em atuação jurídica coloca o caso de cobrança em
 * retenção LEGAL no collection e o encerramento a retira.
 */
public interface LegalMatterUseCases {

    /**
     * Indicação manual de um caso de cobrança (exige {@code LEGAL_MANAGE}). Os títulos são os
     * indicáveis do caso (ou o subconjunto informado); com a política padrão, títulos prescritos
     * são excluídos e sinalizados. Caso jurídico já aberto → conflito {@code legal_matter.already_open}.
     */
    MatterView refer(ReferCommand command);

    MatterView get(UUID matterId);

    MatterSheet sheet(UUID matterId);

    PageResponse<MatterView> search(MatterFilter filter, PageQuery page);

    List<StageEntryView> history(UUID matterId);

    /**
     * Garantias que cobrem os títulos do caso jurídico (lidas do debt pelo contrato de origem de
     * cada título), sem duplicidade; a identificação do bem vem resumida.
     */
    List<MatterCollateralView> collaterals(UUID matterId);

    /**
     * Transição de fase. ELIGIBLE → UNDER_REVIEW (aceite da indicação) exige {@code LEGAL_AUTHORIZE};
     * UNDER_REVIEW → AWAITING_AUTHORIZATION abre a solicitação de autorização ao credor com as vias
     * e as custas da viabilidade vigente.
     */
    MatterView transition(UUID matterId, TransitionCommand command);

    MatterView assign(UUID matterId, AssignCommand command);

    /** Acordos do negotiation vinculados ao caso jurídico (por id, conhecidos pelos eventos). */
    List<AgreementLinkView> agreements(UUID matterId);

    /** Painel jurídico: casos por fase, valores, prazos vencendo e perdidos no escopo do ator. */
    Dashboard dashboard(UUID creditorId);

    /** @param titleIds subconjunto dos títulos indicáveis do caso (vazio = todos) */
    record ReferCommand(UUID collectionCaseId, Set<UUID> titleIds, String reason) {
    }

    /**
     * @param stage         ELIGIBLE, UNDER_REVIEW, AWAITING_AUTHORIZATION, EXTRAJUDICIAL, FILED,
     *                      IN_PROGRESS, ENFORCEMENT, SETTLED_IN_COURT, CLOSED, NOT_VIABLE ou RETURNED_TO_CREDITOR
     * @param closureReason obrigatório para CLOSED: PAID, AGREEMENT_FULFILLED, ASSET_RECOVERED,
     *                      UNCOLLECTIBLE, PRESCRIBED, WITHDRAWN, CREDITOR_REQUEST ou OTHER
     * @param agreementId   acordo judicial (negotiation) para SETTLED_IN_COURT
     */
    record TransitionCommand(String stage, String reason, String closureReason, UUID agreementId) {
    }

    /**
     * @param responsibleType INTERNAL (usuário interno) ou LAW_FIRM (escritório e, opcionalmente, o advogado)
     */
    record AssignCommand(String responsibleType, UUID userId, UUID lawFirmId) {
    }

    /** Filtros opcionais; {@code open} = somente não finais. */
    record MatterFilter(UUID creditorId, UUID portfolioId, UUID debtorId, UUID collectionCaseId, Set<String> stages,
                        String responsibleType, UUID responsibleUserId, UUID lawFirmId, Boolean open) {
    }

    record MatterView(UUID id, UUID collectionCaseId, UUID debtorId, UUID creditorId, UUID portfolioId, String origin,
                      UUID eligibilityRuleId, String referralNote, Set<UUID> titleIds, Set<String> flags, String stage,
                      String resumeStage, String stageReason, Instant stageChangedAt, Set<String> nextStages,
                      String primaryRoute, String responsibleType, UUID responsibleUserId, UUID lawFirmId,
                      BigDecimal debtAmount, BigDecimal expectedCosts, BigDecimal estimatedRecoverable,
                      UUID authorizationId, BigDecimal authorizedBudget, BigDecimal expensesTotal,
                      BigDecimal recoveredAmount, UUID agreementId, boolean collectionHold, Instant referredAt,
                      Instant closedAt, String closureReason, long version) {
    }

    /**
     * Ficha do caso jurídico. O devedor aparece com documento mascarado.
     */
    record MatterSheet(MatterView matter, DebtorInfo debtor, List<TitleInfo> titles,
                       LegalViabilityUseCases.AssessmentView viability,
                       List<CreditorAuthorizationUseCases.AuthorizationView> authorizations,
                       List<ExtrajudicialMeasureUseCases.MeasureView> measures,
                       List<LawsuitUseCases.LawsuitView> lawsuits,
                       List<LegalDeadlineUseCases.DeadlineView> deadlines,
                       List<LegalExpenseUseCases.ExpenseView> expenses,
                       List<LegalDocumentUseCases.DocumentView> documents,
                       List<StageEntryView> history,
                       List<MatterCollateralView> collaterals,
                       List<HearingUseCases.HearingView> hearings) {
    }

    /**
     * @param type           tipo da garantia (ex.: VEHICLE_FIDUCIARY_ALIENATION)
     * @param assetSummary   identificação resumida do bem (ex.: "veículo ABC-1234")
     * @param status         situação da garantia no debt (ACTIVE, UNDER_ENFORCEMENT, SEIZED…)
     * @param estimatedValue valor recuperável estimado (última avaliação); nulo sem avaliação
     * @param titleIds       títulos do caso jurídico cobertos pela garantia
     */
    record MatterCollateralView(UUID collateralId, UUID contractId, String type, String description,
                                String assetSummary, String status, BigDecimal estimatedValue,
                                LocalDate lastAppraisalDate, Set<UUID> titleIds) {
    }

    record DebtorInfo(UUID id, String name, String maskedDocument, String personType) {
    }

    record TitleInfo(UUID titleId, String externalId, Integer installmentNumber, LocalDate dueDate,
                     BigDecimal openBalance, String status, long daysOverdue) {
    }

    record StageEntryView(UUID id, String fromStage, String toStage, String reason, String closureReason, String cause,
                          Instant occurredAt, UUID actorId) {
    }

    /** @param status última situação conhecida (FORMALIZED, IN_PAYMENT, SETTLED, BROKEN, CANCELLED, RENEGOTIATED) */
    record AgreementLinkView(UUID agreementId, String status, boolean judicial, boolean broken,
                             UUID replacedAgreementId, Instant lastEventAt) {
    }

    record StageCount(String stage, long matters, BigDecimal debtAmount, BigDecimal expensesTotal,
                      BigDecimal recoveredAmount) {
    }

    /**
     * @param deadlinesDueSoon prazos pendentes que vencem nos próximos 5 dias corridos (inclusive hoje)
     * @param deadlinesMissed  prazos perdidos de casos não finais
     */
    record Dashboard(List<StageCount> stages, long openMatters, BigDecimal openDebtAmount, BigDecimal expensesTotal,
                     BigDecimal recoveredAmount, long deadlinesDueSoon, long deadlinesMissed) {
    }
}
