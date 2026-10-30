package br.com.gestorcobranca.publicapi.web.v1;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeView;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.ContactView;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases.DebtorView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportBatchView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportIssueView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.InstallmentView;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorView;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.PortfolioView;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.ContractView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Contrato público v1 (DTOs estáveis, separados das visões internas dos casos de uso). Minimização (LGPD): devedor só
 * com nome e documento mascarado; contatos mascarados; nada de observações internas, operadores, IP/agente de aceite
 * ou dados de outros credores.
 */
final class PublicApiModels {

    private PublicApiModels() {
    }

    @Schema(name = "Creditor", description = "Credor dono do token")
    record CreditorDto(UUID id, String legalName, String tradeName, String document, String externalCode, String status) {
        static CreditorDto of(CreditorView v) {
            return new CreditorDto(v.id(), v.legalName(), v.tradeName(), v.document(), v.externalCode(), v.status());
        }
    }

    @Schema(name = "ServiceContract", description = "Contrato de prestação de serviço entre o credor e a assessoria")
    record ContractDto(UUID id, String number, LocalDate startDate, LocalDate endDate, Integer returnAfterDays,
                       int settlementDay, String status) {
        static ContractDto of(ContractView v) {
            return new ContractDto(v.id(), v.number(), v.startDate(), v.endDate(), v.returnAfterDays(),
                    v.settlementDay(), v.status());
        }
    }

    @Schema(name = "Portfolio", description = "Carteira do credor")
    record PortfolioDto(UUID id, UUID contractId, String code, String name, String description, LocalDate receivedOn,
                        LocalDate validUntil,
                        @Schema(allowableValues = {"DRAFT", "ACTIVE", "SUSPENDED", "RETURNED", "CLOSED"}) String status,
                        String statusReason,
                        @Schema(description = "Ativa e dentro da vigência hoje") boolean operable,
                        @Schema(description = "Versão (também no cabeçalho ETag)") long version, Instant updatedAt) {
        static PortfolioDto of(PortfolioView v) {
            return new PortfolioDto(v.id(), v.contractId(), v.code(), v.name(), v.description(), v.receivedOn(),
                    v.validUntil(), v.status(), v.statusReason(), v.operable(), v.version(), v.updatedAt());
        }
    }

    @Schema(name = "ImportBatch", description = "Lote de importação (processamento assíncrono)")
    record ImportBatchDto(UUID id, UUID portfolioId, String source,
                          @Schema(allowableValues = {"IMPORT", "VALIDATE_ONLY"}) String mode,
                          @Schema(description = "RECEIVED, PROCESSING, COMPLETED, COMPLETED_WITH_ERRORS, VALIDATED, FAILED, "
                                  + "CANCELLED") String status,
                          String statusReason, String fileName, long fileSize, String fileSha256, Long totalRows,
                          Integer progressPercent, ImportCountersDto counters, int issuesRecorded, boolean issuesTruncated,
                          Instant createdAt, Instant startedAt, Instant finishedAt) {
        static ImportBatchDto of(ImportBatchView v) {
            var c = v.counters();
            return new ImportBatchDto(v.id(), v.portfolioId(), v.source(), v.mode(), v.status(), v.statusReason(),
                    v.fileName(), v.fileSize(), v.fileSha256(), v.totalRows(), v.progressPercent(),
                    new ImportCountersDto(c.rowsRead(), c.rowsValid(), c.rowsWithError(), c.errors(), c.warnings(),
                            c.debtorsCreated(), c.debtorsUpdated(), c.titlesCreated(), c.titlesUpdated(),
                            c.titlesUnchanged(), c.titlesSkipped(), c.titlesSuspended(), c.principalTotal()),
                    v.issuesRecorded(), v.issuesTruncated(), v.createdAt(), v.startedAt(), v.finishedAt());
        }
    }

    @Schema(name = "ImportCounters")
    record ImportCountersDto(int rowsRead, int rowsValid, int rowsWithError, int errors, int warnings, int debtorsCreated,
                             int debtorsUpdated, int titlesCreated, int titlesUpdated, int titlesUnchanged,
                             int titlesSkipped, int titlesSuspended, BigDecimal principalTotal) {
    }

    @Schema(name = "ImportIssue", description = "Ocorrência de uma linha (valor sempre mascarado)")
    record ImportIssueDto(long rowNumber, @Schema(allowableValues = {"ERROR", "WARNING"}) String severity, String field,
                          String code, String message, String valueExcerpt) {
        static ImportIssueDto of(ImportIssueView v) {
            return new ImportIssueDto(v.rowNumber(), v.severity(), v.field(), v.code(), v.message(), v.valueExcerpt());
        }
    }

    @Schema(name = "Title", description = "Título (dívida) do credor")
    record TitleDto(UUID id, UUID portfolioId, UUID debtorId,
                    @Schema(description = "Identificador do título no credor (id_titulo da carga)") String externalId,
                    Integer installmentNumber, LocalDate dueDate, BigDecimal principalAmount, BigDecimal openBalance,
                    BigDecimal paidTotal,
                    @Schema(allowableValues = {"OPEN", "SETTLED", "CANCELLED", "SUSPENDED", "RETURNED"}) String status,
                    String statusReason, boolean disputeBlocked, Instant lastPaymentAt, long daysOverdue,
                    @Schema(description = "Versão (também no cabeçalho ETag)") long version, Instant updatedAt) {
        static TitleDto of(TitleView v) {
            return new TitleDto(v.id(), v.portfolioId(), v.debtorId(), v.externalId(), v.installmentNumber(),
                    v.dueDate(), v.principalAmount(), v.openBalance(), v.paidTotal(), v.status(), v.statusReason(),
                    v.disputeBlocked(), v.lastPaymentAt(), v.daysOverdue(), v.version(), v.updatedAt());
        }
    }

    @Schema(name = "Debtor", description = "Devedor — dados mínimos, documento sempre mascarado")
    record DebtorDto(UUID id, @Schema(allowableValues = {"INDIVIDUAL", "COMPANY"}) String personType, String name,
                     String maskedDocument, @Schema(allowableValues = {"ACTIVE", "ANONYMIZED"}) String status) {
        static DebtorDto of(DebtorView v) {
            return new DebtorDto(v.id(), v.personType(), v.name(), v.maskedDocument(), v.status());
        }
    }

    @Schema(name = "DebtorContact", description = "Contato do devedor, sempre mascarado")
    record ContactDto(UUID id, @Schema(allowableValues = {"PHONE", "EMAIL", "ADDRESS"}) String type, String maskedValue,
                      String label, boolean whatsapp, String source,
                      @Schema(allowableValues = {"VALID", "INVALID"}) String status, int priority) {
        static ContactDto of(ContactView v) {
            return new ContactDto(v.id(), v.type(), v.maskedValue(), v.label(), v.whatsapp(), v.source(), v.status(),
                    v.priority());
        }
    }

    @Schema(name = "CollectionCase", description = "Situação da cobrança de um devedor numa carteira (somente leitura)")
    record CaseDto(UUID id, UUID debtorId, UUID portfolioId, String status, Instant openedAt, Instant closedAt,
                   BigDecimal openBalance, int openTitles, int suspendedTitles, int disputedTitles, long daysOverdue,
                   boolean eligible, List<String> eligibilityReasons, Instant lastInteractionAt,
                   Instant lastEffectiveContactAt, int interactionCount, long version) {
        static CaseDto of(CaseView v) {
            return new CaseDto(v.id(), v.debtorId(), v.portfolioId(), v.status(), v.openedAt(), v.closedAt(),
                    v.openBalance(), v.openTitles(), v.suspendedTitles(), v.disputedTitles(), v.daysOverdue(),
                    v.eligible(), v.eligibilityReasons(), v.lastInteractionAt(), v.lastEffectiveContactAt(),
                    v.interactionCount(), v.version());
        }
    }

    @Schema(name = "Agreement", description = "Acordo formalizado (somente leitura)")
    record AgreementDto(UUID id, String status, UUID debtorId, UUID portfolioId, String channel, BigDecimal updatedAmount,
                        BigDecimal discountAmount, BigDecimal totalAmount, BigDecimal downPayment, BigDecimal paidAmount,
                        BigDecimal remainingAmount, List<UUID> titleIds, List<InstallmentDto> installments,
                        Instant formalizedAt, Instant settledAt, Instant endedAt, UUID replacedAgreementId,
                        long version) {
        static AgreementDto of(AgreementView v) {
            return new AgreementDto(v.id(), v.status(), v.debtorId(), v.portfolioId(), v.channel(), v.updatedAmount(),
                    v.discountAmount(), v.totalAmount(), v.downPayment(), v.paidAmount(), v.remainingAmount(),
                    v.titleIds(), v.installments().stream().map(InstallmentDto::of).toList(), v.formalizedAt(),
                    v.settledAt(), v.endedAt(), v.replacedAgreementId(), v.version());
        }
    }

    @Schema(name = "AgreementInstallment")
    record InstallmentDto(UUID id, int number, LocalDate dueDate, BigDecimal amount, BigDecimal paidAmount,
                          String status, Instant paidAt) {
        static InstallmentDto of(InstallmentView v) {
            return new InstallmentDto(v.id(), v.number(), v.dueDate(), v.amount(), v.paidAmount(), v.status(), v.paidAt());
        }
    }

    @Schema(name = "Payment", description = "Pagamento (somente leitura; sem dados do pagador)")
    record PaymentDto(UUID id, String source, String method,
                      @Schema(allowableValues = {"INFORMED", "CONFIRMED", "RECONCILED", "REJECTED", "REVERSED"}) String status,
                      UUID debtorId, BigDecimal amount, Instant paidAt, String externalReference, String externalOrigin,
                      Instant informedAt, Instant confirmedAt, Instant reconciledAt, Instant reversedAt,
                      BigDecimal allocatedAmount, BigDecimal unallocatedAmount, List<AllocationDto> allocations,
                      long version) {
        static PaymentDto of(PaymentView v) {
            return new PaymentDto(v.id(), v.source(), v.method(), v.status(), v.debtorId(), v.amount(), v.paidAt(),
                    v.externalReference(), v.externalOrigin(), v.informedAt(), v.confirmedAt(), v.reconciledAt(),
                    v.reversedAt(), v.allocatedAmount(), v.unallocatedAmount(),
                    v.allocations().stream().map(a -> new AllocationDto(a.id(), a.kind(), a.status(), a.agreementId(),
                            a.amount(), a.lines().stream().map(l -> new AllocationLineDto(l.installmentId(), l.titleId(),
                                    l.amount())).toList())).toList(), v.version());
        }
    }

    @Schema(name = "PaymentAllocation")
    record AllocationDto(UUID id, String kind, String status, UUID agreementId, BigDecimal amount,
                         List<AllocationLineDto> lines) {
    }

    @Schema(name = "PaymentAllocationLine")
    record AllocationLineDto(UUID installmentId, UUID titleId, BigDecimal amount) {
    }

    @Schema(name = "Dispute", description = "Contestação")
    record DisputeDto(UUID id, UUID caseId, UUID debtorId, UUID portfolioId, List<UUID> titleIds, String type,
                      String origin, String status, boolean blocksCollection, BigDecimal disputedAmount, Instant openedAt,
                      LocalDate responseDueDate, boolean overdue, String outcome, Instant resolvedAt, long version) {
        static DisputeDto of(DisputeView v) {
            return new DisputeDto(v.id(), v.caseId(), v.debtorId(), v.portfolioId(), List.copyOf(v.titleIds()), v.type(),
                    v.origin(), v.status(), v.blocksCollection(), v.disputedAmount(), v.openedAt(), v.responseDueDate(),
                    v.overdue(), v.outcome(), v.resolvedAt(), v.version());
        }
    }
}
