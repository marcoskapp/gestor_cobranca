package br.com.gestorcobranca.legal.web;

import br.com.gestorcobranca.legal.application.port.in.CreditorAuthorizationUseCases.DecisionCommand;
import br.com.gestorcobranca.legal.application.port.in.CreditorAuthorizationUseCases.RequestCommand;
import br.com.gestorcobranca.legal.application.port.in.ExtrajudicialMeasureUseCases.MeasureCommand;
import br.com.gestorcobranca.legal.application.port.in.ExtrajudicialMeasureUseCases.StatusCommand;
import br.com.gestorcobranca.legal.application.port.in.ExtrajudicialMeasureUseCases.SubmitCommand;
import br.com.gestorcobranca.legal.application.port.in.HearingUseCases.HearingCommand;
import br.com.gestorcobranca.legal.application.port.in.LawFirmUseCases.LawFirmCommand;
import br.com.gestorcobranca.legal.application.port.in.LawsuitUseCases.LawsuitCommand;
import br.com.gestorcobranca.legal.application.port.in.LawsuitUseCases.MovementCommand;
import br.com.gestorcobranca.legal.application.port.in.LawsuitUseCases.UpdateLawsuitCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalDeadlineUseCases.DeadlineCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalDeadlineUseCases.SuspensionCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalDocumentUseCases.UploadCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalEligibilityUseCases.RuleCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalExpenseUseCases.ExpenseCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.AssignCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.ReferCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalMatterUseCases.TransitionCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalSettingsUseCases.SettingsCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalViabilityUseCases.AssessmentCommand;
import br.com.gestorcobranca.legal.application.port.in.LegalViabilityUseCases.AssessmentInput;
import br.com.gestorcobranca.legal.application.port.in.LegalViabilityUseCases.ChecklistEntry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Requests da API do jurídico (Bean Validation na borda; regras de negócio no domínio). */
final class LegalRequests {

    private LegalRequests() {
    }

    record SettingsRequest(@NotBlank @Size(max = 20) String conductModel,
                           @NotEmpty @Size(max = 3) Set<@NotBlank @Size(max = 20) String> allowedExpenseAdvancers,
                           @NotBlank @Size(max = 10) String budgetEnforcement,
                           @NotBlank @Size(max = 10) String prescribedTitlePolicy,
                           @NotBlank @Size(max = 20) String collectionHoldStage,
                           @NotEmpty @Size(max = 5) List<@NotNull @Min(0) @Max(30) Integer> deadlineAlertLevels,
                           boolean yearEndRecess,
                           @Min(1) @Max(30) int removalDeadlineBusinessDays,
                           @Min(1) @Max(60) int homologationDeadlineBusinessDays,
                           @Min(1) @Max(60) int creditBureauNoticeDays,
                           @Min(1) @Max(1825) int prescriptionWarningDays,
                           @Min(0) @Max(1825) int referralCooldownDays) {

        SettingsCommand toCommand() {
            return new SettingsCommand(conductModel, allowedExpenseAdvancers, budgetEnforcement, prescribedTitlePolicy,
                    collectionHoldStage, deadlineAlertLevels, yearEndRecess, removalDeadlineBusinessDays,
                    homologationDeadlineBusinessDays, creditBureauNoticeDays, prescriptionWarningDays, referralCooldownDays);
        }
    }

    /** Na alteração, credor e carteira são ignorados (imutáveis). */
    record RuleRequest(@NotNull UUID creditorId, UUID portfolioId, @NotBlank @Size(max = 120) String name,
                       @Min(0) @Max(36500) Integer minDaysOverdue,
                       @DecimalMin("0.00") @Digits(integer = 15, fraction = 2) BigDecimal minOpenBalance,
                       boolean requireCollateral, boolean requireExecutiveTitle,
                       @Min(1) @Max(100) Integer minBrokenAgreements,
                       @Min(1) @Max(3650) Integer prescriptionWithinDays) {

        RuleCommand toCommand() {
            return new RuleCommand(creditorId, portfolioId, name, minDaysOverdue, minOpenBalance, requireCollateral,
                    requireExecutiveTitle, minBrokenAgreements, prescriptionWithinDays);
        }
    }

    record ReferRequest(@NotNull UUID collectionCaseId, @Size(max = 500) Set<UUID> titleIds,
                        @NotBlank @Size(max = 500) String reason) {

        ReferCommand toCommand() {
            return new ReferCommand(collectionCaseId, titleIds, reason);
        }
    }

    record TransitionRequest(@NotBlank @Size(max = 30) String stage, @NotBlank @Size(max = 500) String reason,
                             @Size(max = 30) String closureReason, UUID agreementId) {

        TransitionCommand toCommand() {
            return new TransitionCommand(stage, reason, closureReason, agreementId);
        }
    }

    record AssignRequest(@NotBlank @Size(max = 20) String responsibleType, UUID userId, UUID lawFirmId) {

        AssignCommand toCommand() {
            return new AssignCommand(responsibleType, userId, lawFirmId);
        }
    }

    record InputRequest(@DecimalMin("0.00") @Digits(integer = 15, fraction = 2) BigDecimal otherAssetsValue,
                        @DecimalMin("0") @DecimalMax("100") BigDecimal realizationRate,
                        @DecimalMin("0") @DecimalMax("100") BigDecimal successProbability,
                        @DecimalMin("0.00") @Digits(integer = 15, fraction = 2) BigDecimal expectedCosts) {

        AssessmentInput toInput() {
            return new AssessmentInput(otherAssetsValue, realizationRate, successProbability, expectedCosts);
        }
    }

    record ChecklistRequest(@NotBlank @Size(max = 40) String code, @NotBlank @Size(max = 150) String label,
                            @NotBlank @Size(max = 20) String status, boolean required) {
    }

    record AssessmentRequest(@NotNull @Valid InputRequest input, boolean viable,
                             @Size(max = 7) List<@NotBlank @Size(max = 40) String> recommendedRoutes,
                             @Size(max = 50) List<@Valid ChecklistRequest> checklist,
                             @Size(max = 6000) String assetSearchNotes,
                             @Size(max = 8000) String opinion) {

        AssessmentCommand toCommand() {
            return new AssessmentCommand(input.toInput(), viable, recommendedRoutes, checklist == null ? null
                    : checklist.stream().map(c -> new ChecklistEntry(c.code(), c.label(), c.status(), c.required())).toList(),
                    assetSearchNotes, opinion);
        }
    }

    record AuthorizationRequest(@NotEmpty @Size(max = 7) Set<@NotBlank @Size(max = 40) String> routes,
                                @NotNull @DecimalMin("0.00") @Digits(integer = 15, fraction = 2) BigDecimal budget,
                                @Size(max = 500) String note) {

        RequestCommand toCommand() {
            return new RequestCommand(routes, budget, note);
        }
    }

    record AuthorizationDecisionRequest(@NotBlank @Size(max = 20) String decision,
                                        @Size(max = 7) Set<@NotBlank @Size(max = 40) String> routes,
                                        @DecimalMin("0.00") @Digits(integer = 15, fraction = 2) BigDecimal approvedBudget,
                                        @NotNull @PastOrPresent LocalDate decisionDate,
                                        @NotBlank @Size(max = 20) String channel,
                                        @NotBlank @Size(max = 150) String representative,
                                        UUID evidenceDocumentId,
                                        @Size(max = 500) String note) {

        DecisionCommand toCommand() {
            return new DecisionCommand(decision, routes, approvedBudget, decisionDate, channel, representative,
                    evidenceDocumentId, note);
        }
    }

    record MeasureRequest(@NotBlank @Size(max = 30) String type, @Size(max = 500) Set<UUID> titleIds, UUID collateralId,
                          @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount,
                          @Size(max = 150) String authority, @PastOrPresent LocalDate priorNoticeOn, LocalDate dueDate) {

        MeasureCommand toCommand() {
            return new MeasureCommand(type, titleIds, collateralId, amount, authority, priorNoticeOn, dueDate);
        }
    }

    record SubmitRequest(@NotNull @PastOrPresent LocalDate submittedOn, @Size(max = 80) String protocol) {

        SubmitCommand toCommand() {
            return new SubmitCommand(submittedOn, protocol);
        }
    }

    record MeasureStatusRequest(@NotBlank @Size(max = 20) String status, @PastOrPresent LocalDate date,
                                @Size(max = 500) String reason) {

        StatusCommand toCommand() {
            return new StatusCommand(status, date, reason);
        }
    }

    record LawsuitRequest(@NotBlank @Size(max = 30) String cnjNumber, @NotBlank @Size(max = 40) String route,
                          @Size(max = 150) String actionClass, @Size(max = 10) String creditorPole,
                          @Size(max = 50) Set<UUID> debtorIds,
                          @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal claimAmount,
                          @NotNull @PastOrPresent LocalDate filedOn, @Size(max = 150) String courtName,
                          @Size(max = 200) String courtUnit) {

        LawsuitCommand toCommand() {
            return new LawsuitCommand(cnjNumber, route, actionClass, creditorPole, debtorIds, claimAmount, filedOn,
                    courtName, courtUnit);
        }
    }

    record UpdateLawsuitRequest(@Size(max = 150) String courtName, @Size(max = 200) String courtUnit,
                                @Size(max = 150) String actionClass,
                                @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal claimAmount) {

        UpdateLawsuitCommand toCommand() {
            return new UpdateLawsuitCommand(courtName, courtUnit, actionClass, claimAmount);
        }
    }

    record StatusRequest(@NotBlank @Size(max = 20) String status, @NotBlank @Size(max = 500) String reason) {
    }

    record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    record DeadlineRequest(@NotBlank @Size(max = 30) String kind, @NotBlank @Size(max = 300) String description,
                           UUID lawsuitId, UUID measureId, @PastOrPresent LocalDate startDate,
                           @Size(max = 20) String startEvent, @Min(1) @Max(365) Integer termDays,
                           @Size(max = 20) String countingMode, LocalDate fixedDate, UUID responsibleUserId) {

        DeadlineCommand toCommand() {
            return new DeadlineCommand(kind, description, lawsuitId, measureId, startDate, startEvent, termDays,
                    countingMode, fixedDate, responsibleUserId);
        }
    }

    record MovementRequest(@NotNull @PastOrPresent LocalDate occurredOn, @NotBlank @Size(max = 30) String type,
                           @Size(max = 6000) String description,
                           @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount,
                           UUID collateralId, @Size(max = 20) String collateralStatus,
                           @Valid DeadlineRequest deadline) {

        MovementCommand toCommand() {
            return new MovementCommand(occurredOn, type, description, amount, collateralId, collateralStatus,
                    deadline == null ? null : deadline.toCommand());
        }
    }

    record CompleteRequest(@NotNull @PastOrPresent LocalDate completedOn, @Size(max = 500) String note) {
    }

    record SuspensionRequest(@NotNull LocalDate startDate, @NotNull LocalDate endDate, @Size(max = 5) String courtCode,
                             @NotBlank @Size(max = 300) String reason) {

        SuspensionCommand toCommand() {
            return new SuspensionCommand(startDate, endDate, courtCode, reason);
        }
    }

    record HearingRequest(@Size(max = 30) String type, @NotNull Instant scheduledAt, @NotBlank @Size(max = 20) String mode,
                          @Size(max = 300) String location, @Size(max = 500) String link,
                          @Size(max = 20) Set<UUID> participantUserIds, @Size(max = 2000) String externalParticipants) {

        HearingCommand toCommand() {
            return new HearingCommand(type, scheduledAt, mode, location, link, participantUserIds, externalParticipants);
        }
    }

    record HearingResultRequest(@NotBlank @Size(max = 20) String outcome, @Size(max = 6000) String notes) {
    }

    record ExpenseRequest(@NotBlank @Size(max = 30) String type, @Size(max = 300) String description,
                          @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount,
                          @NotNull @PastOrPresent LocalDate incurredOn, @NotBlank @Size(max = 20) String advancedBy,
                          boolean reimbursable, UUID lawsuitId, UUID receiptDocumentId) {

        ExpenseCommand toCommand() {
            return new ExpenseCommand(type, description, amount, incurredOn, advancedBy, reimbursable, lawsuitId,
                    receiptDocumentId);
        }
    }

    record ReimburseRequest(@NotNull @PastOrPresent LocalDate reimbursedOn, @Size(max = 500) String note) {
    }

    record LawFirmRequest(@NotBlank @Size(max = 150) String name, @Size(max = 20) String cnpj,
                          @Size(max = 40) String oabRegistration, @Size(max = 150) String contactEmail) {

        LawFirmCommand toCommand() {
            return new LawFirmCommand(name, cnpj, oabRegistration, contactEmail);
        }
    }

    record MemberRequest(@NotNull UUID userId) {
    }

    record DocumentRequest(@NotBlank @Size(max = 30) String kind, @Size(max = 20) String linkType, UUID linkId,
                           @Size(max = 300) String description) {

        UploadCommand toCommand() {
            return new UploadCommand(kind, linkType, linkId, description);
        }
    }
}
