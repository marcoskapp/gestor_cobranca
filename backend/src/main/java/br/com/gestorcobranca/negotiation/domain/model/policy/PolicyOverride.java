package br.com.gestorcobranca.negotiation.domain.model.policy;

import br.com.gestorcobranca.shared.domain.Guard;

import java.util.List;
import java.util.UUID;

/**
 * Sobreposição de uma política para uma carteira e/ou faixa de atraso. Cada grupo não nulo
 * substitui integralmente o grupo correspondente das regras base; grupos nulos são herdados.
 * <p>
 * Especificidade (a mais específica que casar vence): carteira + faixa (3) &gt; só carteira (2)
 * &gt; só faixa (1). Sobreposições de mesma especificidade e mesma carteira não podem ter faixas
 * que se interceptem.
 *
 * @param code           identificador legível, registrado na memória de cálculo
 * @param portfolioId    carteira do credor (opcional)
 * @param minDaysOverdue início da faixa de atraso, inclusive (opcional)
 * @param maxDaysOverdue fim da faixa de atraso, inclusive (opcional)
 * @param name           nome de exibição (opcional, até 80 caracteres; ex.: "Atraso longo"); sem ele, as telas
 *                       mostram o código em forma legível
 */
@org.springframework.modulith.NamedInterface("usecases")
public record PolicyOverride(
        String code,
        UUID portfolioId,
        Integer minDaysOverdue,
        Integer maxDaysOverdue,
        PolicyRules.Charges charges,
        List<PolicyRules.DiscountTier> discountTiers,
        PolicyRules.Installments installments,
        PolicyRules.Validity validity,
        List<PolicyRules.ConcessionLevel> ladder,
        PolicyRules.BreakRule breakRule,
        String name) {

    public PolicyOverride {
        discountTiers = discountTiers == null ? null : List.copyOf(discountTiers);
        ladder = ladder == null ? null : List.copyOf(ladder);
        name = name == null || name.isBlank() ? null : name.trim();
    }

    /** Sem nome de exibição (políticas gravadas antes do nome). */
    public PolicyOverride(String code, UUID portfolioId, Integer minDaysOverdue, Integer maxDaysOverdue,
                          PolicyRules.Charges charges, List<PolicyRules.DiscountTier> discountTiers,
                          PolicyRules.Installments installments, PolicyRules.Validity validity,
                          List<PolicyRules.ConcessionLevel> ladder, PolicyRules.BreakRule breakRule) {
        this(code, portfolioId, minDaysOverdue, maxDaysOverdue, charges, discountTiers, installments, validity, ladder,
                breakRule, null);
    }

    boolean hasBand() {
        return minDaysOverdue != null || maxDaysOverdue != null;
    }

    int specificity() {
        return (portfolioId != null ? 2 : 0) + (hasBand() ? 1 : 0);
    }

    long bandStart() {
        return minDaysOverdue == null ? 0 : minDaysOverdue;
    }

    long bandEnd() {
        return maxDaysOverdue == null ? Long.MAX_VALUE : maxDaysOverdue;
    }

    boolean matches(UUID portfolio, long daysOverdue) {
        boolean portfolioOk = portfolioId == null || portfolioId.equals(portfolio);
        boolean bandOk = !hasBand() || (daysOverdue >= bandStart() && daysOverdue <= bandEnd());
        return portfolioOk && bandOk;
    }

    boolean conflictsWith(PolicyOverride other) {
        if (specificity() != other.specificity() || !java.util.Objects.equals(portfolioId, other.portfolioId)) {
            return false;
        }
        if (!hasBand()) {
            return true;
        }
        return bandStart() <= other.bandEnd() && other.bandStart() <= bandEnd();
    }

    void validate(PolicyRules base) {
        Guard.require(code != null && code.matches("[A-Z0-9_]{2,40}"), "policy.terms.override_code_invalid",
                "Código da sobreposição: 2 a 40 caracteres em maiúsculas, dígitos ou _");
        Guard.require(name == null || name.length() <= 80, "policy.terms.override_name_too_long",
                "Nome da sobreposição " + code + ": até 80 caracteres");
        Guard.require(specificity() > 0, "policy.terms.override_without_selector",
                "Sobreposição " + code + " precisa de carteira ou faixa de atraso");
        Guard.require(minDaysOverdue == null || minDaysOverdue >= 0, "policy.terms.override_band_invalid",
                "Faixa de atraso inválida em " + code);
        Guard.require(maxDaysOverdue == null || maxDaysOverdue >= (minDaysOverdue == null ? 0 : minDaysOverdue),
                "policy.terms.override_band_invalid", "Faixa de atraso inválida em " + code);
        Guard.require(charges != null || discountTiers != null || installments != null || validity != null
                        || ladder != null || breakRule != null, "policy.terms.override_empty",
                "Sobreposição " + code + " não altera nenhum grupo");
        // as regras resultantes precisam ser válidas por si
        base.overriddenBy(this).validate();
    }
}
