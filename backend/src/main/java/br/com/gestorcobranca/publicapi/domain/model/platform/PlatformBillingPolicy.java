package br.com.gestorcobranca.publicapi.domain.model.platform;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * Política de cobrança da plataforma para uma assessoria (segunda camada do faturamento da API pública: a plataforma
 * cobra da assessoria pelo consumo de <i>todos</i> os tokens dela, de todos os credores), independente das condições
 * que a assessoria cobra de cada credor no contrato. Uma por assessoria, gravada no tenant da própria assessoria (que
 * a lê sob RLS, sem alterar) e mantida só pela equipe da plataforma. Define a vigência; preços e dia de fechamento
 * ficam nas versões ({@link PlatformBillingPolicyVersion}).
 * <p>
 * Os ciclos começam no início da vigência e o último termina no fim dela (ciclos proporcionais, como no contrato do
 * credor). O início só muda enquanto não há extrato; o fim não pode ficar no passado (sem cobrança retroativa).
 */
@Entity
@Table(name = "platform_billing_policy", schema = "publicapi")
public class PlatformBillingPolicy extends TenantScopedEntity {

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    protected PlatformBillingPolicy() {
    }

    public PlatformBillingPolicy(LocalDate startDate, LocalDate endDate, LocalDate today) {
        Guard.require(startDate != null, "platform_policy.start_required", "Informe o início da vigência");
        requireEnd(startDate, endDate, today, true);
        this.startDate = startDate;
        this.endDate = endDate;
    }

    /**
     * Altera a vigência.
     *
     * @param hasStatements a assessoria já tem extrato da plataforma (o início fica fixo)
     */
    public void changeTerm(LocalDate newStart, LocalDate newEnd, boolean hasStatements, LocalDate today) {
        Guard.require(newStart != null, "platform_policy.start_required", "Informe o início da vigência");
        Guard.requireState(newStart.equals(startDate) || !hasStatements, "platform_policy.start_locked",
                "O início da vigência não muda depois do primeiro extrato");
        requireEnd(newStart, newEnd, today, newEnd != null && !newEnd.equals(endDate));
        this.startDate = newStart;
        this.endDate = newEnd;
    }

    /** @param endChanged o fim é novo: não pode ficar no passado (um fim já gravado e mantido não é reavaliado) */
    private static void requireEnd(LocalDate start, LocalDate end, LocalDate today, boolean endChanged) {
        if (end == null) {
            return;
        }
        Guard.require(!end.isBefore(start), "platform_policy.term_invalid",
                "O fim da vigência não pode ser anterior ao início");
        Guard.require(!endChanged || !end.isBefore(today), "platform_policy.end_in_past",
                "O fim da vigência não pode ficar no passado (sem cobrança retroativa)");
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }
}
