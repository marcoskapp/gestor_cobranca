package br.com.gestorcobranca.organization.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Contrato de prestação de serviço entre a assessoria e um credor. Define vigência e regras
 * operacionais gerais (prazo de devolução de títulos, exigência de aprovação do credor).
 * Regras de negociação e comissão são versionadas nos módulos {@code negotiation} e
 * {@code settlement}, referenciando este contrato.
 */
@Entity
@Table(name = "service_contract", schema = "organization")
public class ServiceContract extends TenantScopedEntity {

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "number", nullable = false, length = 60)
    private String number;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    /** Dias após o recebimento em que títulos não recuperados devem ser devolvidos ao credor. */
    @Column(name = "return_after_days")
    private Integer returnAfterDays;

    /** Dia do mês de fechamento da prestação de contas. */
    @Column(name = "settlement_day", nullable = false)
    private int settlementDay;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ContractStatus status;

    protected ServiceContract() {
    }

    public ServiceContract(UUID creditorId, String number, LocalDate startDate, LocalDate endDate,
                           Integer returnAfterDays, int settlementDay, String notes) {
        Guard.require(creditorId != null, "contract.creditor_required", "Credor obrigatório");
        Guard.require(number != null && !number.isBlank(), "contract.number_required", "Número do contrato obrigatório");
        this.creditorId = creditorId;
        this.number = number.trim();
        this.status = ContractStatus.ACTIVE;
        defineTerms(startDate, endDate, returnAfterDays, settlementDay, notes);
    }

    public void defineTerms(LocalDate startDate, LocalDate endDate, Integer returnAfterDays, int settlementDay, String notes) {
        Guard.require(startDate != null, "contract.start_required", "Início da vigência obrigatório");
        Guard.require(endDate == null || !endDate.isBefore(startDate), "contract.period_invalid",
                "Fim da vigência não pode ser anterior ao início");
        Guard.require(returnAfterDays == null || returnAfterDays > 0, "contract.return_days_invalid",
                "Prazo de devolução deve ser positivo");
        Guard.require(settlementDay >= 1 && settlementDay <= 28, "contract.settlement_day_invalid",
                "Dia de fechamento deve estar entre 1 e 28");
        this.startDate = startDate;
        this.endDate = endDate;
        this.returnAfterDays = returnAfterDays;
        this.settlementDay = settlementDay;
        this.notes = notes;
    }

    public boolean isEffectiveOn(LocalDate date) {
        return status == ContractStatus.ACTIVE && !date.isBefore(startDate) && (endDate == null || !date.isAfter(endDate));
    }

    public void terminate(LocalDate endDate) {
        Guard.requireState(status == ContractStatus.ACTIVE, "contract.not_active", "Contrato não está ativo");
        Guard.require(endDate != null && !endDate.isBefore(startDate), "contract.period_invalid", "Data de encerramento inválida");
        this.endDate = endDate;
        this.status = ContractStatus.TERMINATED;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public String getNumber() {
        return number;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public Integer getReturnAfterDays() {
        return returnAfterDays;
    }

    public int getSettlementDay() {
        return settlementDay;
    }

    public String getNotes() {
        return notes;
    }

    public ContractStatus getStatus() {
        return status;
    }
}
