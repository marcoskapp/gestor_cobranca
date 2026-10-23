package br.com.gestorcobranca.campaign.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Variante do teste A/B: peso relativo na alocação (entre os não-controle) e alterações de etapas.
 * Uma variante sem alterações é a régua publicada ("campeã" atual).
 */
public record ExperimentVariant(String code, String name, int weight, List<StepOverride> overrides) {

    public static final String CONTROL = "CONTROL";
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");

    public ExperimentVariant {
        overrides = overrides == null ? List.of() : List.copyOf(overrides);
    }

    public ExperimentVariant validate() {
        Guard.require(code != null && CODE.matcher(code).matches() && !CONTROL.equals(code), "experiment.variant_code_invalid",
                "Código de variante inválido (CONTROL é reservado)");
        Guard.require(name != null && !name.isBlank() && name.length() <= 120, "experiment.variant_name_invalid",
                "Nome da variante obrigatório (até 120)");
        Guard.require(weight >= 1 && weight <= 100, "experiment.variant_weight_invalid", "Peso entre 1 e 100");
        Set<String> steps = new HashSet<>();
        overrides.forEach(o -> Guard.require(o != null && steps.add(o.stepCode()), "experiment.override_invalid",
                "Uma alteração por etapa"));
        return this;
    }

    public Optional<StepOverride> overrideFor(String stepCode) {
        return overrides.stream().filter(o -> o.stepCode().equals(stepCode)).findFirst();
    }
}
