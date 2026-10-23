package br.com.gestorcobranca.legal.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Suspensão forense cadastrada pela assessoria (indisponibilidade de sistema, feriado forense
 * local, suspensão determinada pelo tribunal): período sem contagem de prazos, para um tribunal
 * ({@code J.TR}, ex.: {@code 8.26}) ou todos. Feriados nacionais/locais ficam no calendário do
 * compliance; o recesso de fim de ano (CPC art. 220) é parâmetro da assessoria.
 */
@Entity
@Table(name = "court_suspension", schema = "legal")
public class CourtSuspension extends TenantScopedEntity {

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false, updatable = false)
    private LocalDate endDate;

    @Column(name = "court_code", length = 5, updatable = false)
    private String courtCode;

    @Column(name = "reason", nullable = false, length = 300, updatable = false)
    private String reason;

    protected CourtSuspension() {
    }

    public CourtSuspension(LocalDate startDate, LocalDate endDate, String courtCode, String reason) {
        Guard.require(startDate != null && endDate != null && !endDate.isBefore(startDate),
                "legal_suspension.range_invalid", "Período inválido");
        Guard.require(ChronoUnit.DAYS.between(startDate, endDate) <= 366, "legal_suspension.range_too_long",
                "Suspensão de até um ano");
        Guard.require(courtCode == null || courtCode.matches("[1-9]\\.\\d{2}"), "legal_suspension.court_invalid",
                "Tribunal no formato J.TR (ex.: 8.26)");
        Guard.require(reason != null && !reason.isBlank() && reason.trim().length() <= 300,
                "legal_suspension.reason_required", "Motivo obrigatório (até 300 caracteres)");
        this.startDate = startDate;
        this.endDate = endDate;
        this.courtCode = courtCode;
        this.reason = reason.trim();
    }

    public boolean appliesTo(String court) {
        return courtCode == null || courtCode.equals(court);
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public String getCourtCode() {
        return courtCode;
    }

    public String getReason() {
        return reason;
    }
}
