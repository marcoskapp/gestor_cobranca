package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.incentive.domain.model.plan.CommissionPlan;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.stereotype.Component;

/**
 * Serialização das regras de plano com versão de esquema: grava somente regras validadas e lê
 * somente esquemas conhecidos (versões futuras exigem conversão explícita aqui).
 */
@Component
class PlanRulesCodec {

    private final JsonCodec json;

    PlanRulesCodec(JsonCodec json) {
        this.json = json;
    }

    String encode(PlanRules rules) {
        return json.toJson(rules.validate());
    }

    PlanRules decode(CommissionPlan plan) {
        if (plan.getRulesSchemaVersion() != PlanRules.SCHEMA_VERSION) {
            throw new IllegalStateException("Esquema de regras %d do plano %s não suportado"
                    .formatted(plan.getRulesSchemaVersion(), plan.getId()));
        }
        return json.fromJson(plan.getRules(), PlanRules.class).validate();
    }
}
