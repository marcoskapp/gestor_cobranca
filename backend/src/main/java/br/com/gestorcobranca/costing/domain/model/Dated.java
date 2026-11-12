package br.com.gestorcobranca.costing.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

import java.time.LocalDate;

/**
 * Cadastro com vigência {@code [validFrom, validTo]} (datas inclusivas; {@code validTo} nulo = em aberto). Cadastros
 * de custo nunca são apagados: uma nova vigência encerra a anterior na véspera e um encerramento só antecipa o fim.
 * O banco recusa vigências sobrepostas da mesma chave (exclusion constraint).
 */
@MappedSuperclass
public abstract class Dated extends TenantScopedEntity {

    @Column(name = "valid_from", nullable = false, updatable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    protected Dated() {
    }

    protected Dated(LocalDate validFrom) {
        this.validFrom = Guard.requireNonNull(validFrom, "costing.valid_from_required", "Informe o início da vigência");
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public boolean covers(LocalDate day) {
        return !day.isBefore(validFrom) && (validTo == null || !day.isAfter(validTo));
    }

    /** Sobrepõe a vigência {@code [from, to]} ({@code to} nulo = em aberto). */
    public boolean overlaps(LocalDate from, LocalDate to) {
        boolean startsBeforeOtherEnds = to == null || !validFrom.isAfter(to);
        boolean endsAfterOtherStarts = validTo == null || !validTo.isBefore(from);
        return startsBeforeOtherEnds && endsAfterOtherStarts;
    }

    /**
     * Encerra a vigência no dia informado (inclusive). Só antecipa o fim: não reabre nem estende uma vigência já
     * encerrada.
     */
    public void endOn(LocalDate day) {
        Guard.requireNonNull(day, "costing.valid_to_required", "Informe o fim da vigência");
        Guard.require(!day.isBefore(validFrom), "costing.valid_to_before_from",
                "O fim da vigência não pode ser antes do início");
        Guard.requireState(validTo == null || day.isBefore(validTo), "costing.already_ended",
                "A vigência já termina nesta data ou antes");
        this.validTo = day;
    }
}
