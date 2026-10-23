package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.negotiation.domain.model.policy.CollectionPolicy;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyTerms;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.stereotype.Component;

/**
 * Serialização dos termos de política com versão de esquema: grava somente termos validados e lê
 * somente esquemas conhecidos (versões futuras exigem conversão explícita aqui).
 */
@Component
class PolicyTermsCodec {

    private final JsonCodec json;

    PolicyTermsCodec(JsonCodec json) {
        this.json = json;
    }

    String encode(PolicyTerms terms) {
        return json.toJson(terms.validate());
    }

    PolicyTerms decode(CollectionPolicy policy) {
        if (policy.getTermsSchemaVersion() != PolicyTerms.SCHEMA_VERSION) {
            throw new IllegalStateException("Esquema de termos %d da política %s não suportado"
                    .formatted(policy.getTermsSchemaVersion(), policy.getId()));
        }
        return json.fromJson(policy.getTerms(), PolicyTerms.class).validate();
    }
}
