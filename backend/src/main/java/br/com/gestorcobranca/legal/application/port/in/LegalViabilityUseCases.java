package br.com.gestorcobranca.legal.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Análise de viabilidade do caso jurídico (etapa 2): os fatos (dívida, garantias e avaliações,
 * prescrição) vêm do módulo debt no momento da análise; as premissas (outros bens, taxa de
 * realização, probabilidade de êxito, custas previstas), o checklist, a conclusão, as vias e o
 * parecer vêm do usuário. A prévia calcula sem gravar; o registro é imutável e passa a ser a
 * análise vigente. Exige a fase UNDER_REVIEW (ou NOT_VIABLE/AWAITING_AUTHORIZATION, para revisão).
 */
public interface LegalViabilityUseCases {

    ViabilityResultView preview(UUID matterId, AssessmentInput input);

    AssessmentView record(UUID matterId, AssessmentCommand command);

    /** Análise vigente (nula se ainda não houver). */
    AssessmentView current(UUID matterId);

    List<AssessmentView> history(UUID matterId);

    /**
     * @param realizationRate    deságio de liquidação, 0–100 (padrão 70)
     * @param successProbability probabilidade de êxito, 0–100 (padrão 50)
     * @param otherAssetsValue   valor de outros bens localizados na pesquisa patrimonial
     */
    record AssessmentInput(BigDecimal otherAssetsValue, BigDecimal realizationRate, BigDecimal successProbability,
                           BigDecimal expectedCosts) {
    }

    /**
     * @param viable           conclusão: viável (com vias) ou inviável (com parecer)
     * @param recommendedRoutes vias na ordem de preferência (a primeira é a principal)
     */
    record AssessmentCommand(AssessmentInput input, boolean viable, List<String> recommendedRoutes,
                             List<ChecklistEntry> checklist, String assetSearchNotes, String opinion) {
    }

    /** @param status PRESENT, MISSING ou NOT_APPLICABLE */
    record ChecklistEntry(String code, String label, String status, boolean required) {
    }

    record ViabilityResultView(BigDecimal debtAmount, BigDecimal collectibleDebt, BigDecimal collateralValue,
                               BigDecimal collateralRecoverable, BigDecimal otherAssetsRecoverable,
                               BigDecimal estimatedRecoverable, BigDecimal expectedCosts, BigDecimal netBenefit,
                               BigDecimal costBenefitRatio, boolean economicallyViable, Set<String> availableRoutes,
                               List<String> suggestedRoutes, Set<String> flags, List<CollateralFactView> collaterals,
                               List<PrescriptionFactView> prescriptions, List<ChecklistEntry> suggestedChecklist) {
    }

    record CollateralFactView(UUID collateralId, String type, String status, BigDecimal estimatedValue,
                              LocalDate lastAppraisalDate) {
    }

    record PrescriptionFactView(UUID titleId, boolean executiveTitle, LocalDate executionEstimatedDate,
                                boolean executionPrescribed, LocalDate collectionEstimatedDate,
                                boolean collectionPrescribed, boolean ruleValidated) {
    }

    record AssessmentView(UUID id, UUID matterId, BigDecimal debtAmount, BigDecimal collectibleDebt,
                          BigDecimal collateralValue, BigDecimal collateralRecoverable, BigDecimal otherAssetsValue,
                          BigDecimal realizationRate, BigDecimal successProbability, BigDecimal expectedCosts,
                          BigDecimal estimatedRecoverable, BigDecimal netBenefit, BigDecimal costBenefitRatio,
                          boolean economicallyViable, boolean viable, List<String> recommendedRoutes,
                          List<String> availableRoutes, List<String> suggestedRoutes, Set<String> flags,
                          List<ChecklistEntry> checklist, String assetSearchNotes, String opinion,
                          Instant assessedAt, UUID assessedBy) {
    }
}
