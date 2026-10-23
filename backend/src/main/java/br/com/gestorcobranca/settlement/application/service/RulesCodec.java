package br.com.gestorcobranca.settlement.application.service;

import br.com.gestorcobranca.settlement.domain.model.CommissionRuleSet;
import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.stereotype.Component;

/** Regras tipadas ↔ jsonb, validadas na escrita e na leitura; recusa versão de esquema desconhecida. */
@Component
class RulesCodec {

    private final JsonCodec json;

    RulesCodec(JsonCodec json) {
        this.json = json;
    }

    String write(CommissionRules rules) {
        br.com.gestorcobranca.shared.domain.Guard.require(rules != null, "commission_rules.required",
                "Regras de comissão obrigatórias");
        return json.toJson(rules.validated());
    }

    CommissionRules read(CommissionRuleSet ruleSet) {
        if (ruleSet.getRulesSchemaVersion() != CommissionRules.SCHEMA_VERSION) {
            throw new IllegalStateException("Versão de esquema de regras desconhecida: "
                    + ruleSet.getRulesSchemaVersion());
        }
        return json.fromJson(ruleSet.getRules(), CommissionRules.class).validated();
    }
}
