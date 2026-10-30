package br.com.gestorcobranca.negotiation.domain.model.policy;

import br.com.gestorcobranca.shared.domain.Guard;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Conteúdo de uma versão de {@link CollectionPolicy}: regras base do credor e sobreposições por
 * carteira/faixa de atraso. Persistido em JSON com {@link #SCHEMA_VERSION}; toda gravação passa
 * por {@link #validate()}.
 */
@org.springframework.modulith.NamedInterface("usecases")
public record PolicyTerms(PolicyRules base, List<PolicyOverride> overrides) {

    /** Versão do esquema JSON. Mudanças incompatíveis exigem nova versão e conversão no codec. */
    public static final int SCHEMA_VERSION = 1;

    public PolicyTerms {
        overrides = overrides == null ? List.of() : List.copyOf(overrides);
    }

    public PolicyTerms validate() {
        Guard.require(base != null, "policy.terms.base_required", "Regras base obrigatórias");
        base.validate();
        Guard.require(overrides.size() <= 50, "policy.terms.too_many_overrides", "No máximo 50 sobreposições");
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < overrides.size(); i++) {
            PolicyOverride o = overrides.get(i);
            Guard.require(o != null, "policy.terms.override_invalid", "Sobreposição vazia");
            o.validate(base);
            Guard.require(codes.add(o.code()), "policy.terms.override_duplicated", "Sobreposição duplicada: " + o.code());
            for (int j = 0; j < i; j++) {
                Guard.require(!o.conflictsWith(overrides.get(j)), "policy.terms.override_overlap",
                        "Sobreposições " + overrides.get(j).code() + " e " + o.code() + " se interceptam");
            }
        }
        return this;
    }

    /** Carteiras referenciadas pelas sobreposições (validadas contra o credor na aplicação). */
    public Set<UUID> referencedPortfolios() {
        Set<UUID> ids = new HashSet<>();
        overrides.stream().map(PolicyOverride::portfolioId).filter(java.util.Objects::nonNull).forEach(ids::add);
        return ids;
    }

    /** Códigos de índice referenciados (base e sobreposições). */
    public Set<String> referencedIndexes() {
        Set<String> codes = new HashSet<>();
        if (base.charges().correctionIndexCode() != null) {
            codes.add(base.charges().correctionIndexCode());
        }
        overrides.stream().filter(o -> o.charges() != null && o.charges().correctionIndexCode() != null)
                .forEach(o -> codes.add(o.charges().correctionIndexCode()));
        return codes;
    }

    /**
     * Regras efetivas para uma negociação: aplica a sobreposição mais específica que casar.
     *
     * @param portfolioId carteira comum dos títulos (nulo quando os títulos são de carteiras diferentes)
     * @param daysOverdue maior atraso entre os títulos
     */
    public Resolved resolve(UUID portfolioId, long daysOverdue) {
        return overrides.stream().filter(o -> o.matches(portfolioId, daysOverdue))
                .max(Comparator.comparingInt(PolicyOverride::specificity))
                .map(o -> new Resolved(base.overriddenBy(o), o.code()))
                .orElseGet(() -> new Resolved(base, null));
    }

    /** @param overrideCode sobreposição aplicada (nulo = regras base) */
    public record Resolved(PolicyRules rules, String overrideCode) {
    }
}
