package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.campaign.domain.model.CadenceDefinition;
import br.com.gestorcobranca.campaign.domain.model.ExperimentVariant;
import br.com.gestorcobranca.campaign.domain.model.SegmentCriteria;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.util.List;

/** Conversão dos valores tipados do módulo (filtros, régua, variantes) para o JSON das entidades. */
@Component
class CampaignCodec {

    private static final TypeReference<List<ExperimentVariant>> VARIANTS = new TypeReference<>() {
    };

    private final JsonCodec json;

    CampaignCodec(JsonCodec json) {
        this.json = json;
    }

    String criteria(SegmentCriteria criteria) {
        return json.toJson(criteria);
    }

    SegmentCriteria criteria(String value) {
        return json.fromJson(value, SegmentCriteria.class);
    }

    String cadence(CadenceDefinition definition) {
        return json.toJson(definition);
    }

    CadenceDefinition cadence(String value) {
        return json.fromJson(value, CadenceDefinition.class);
    }

    String variants(List<ExperimentVariant> variants) {
        return json.toJson(variants);
    }

    List<ExperimentVariant> variants(String value) {
        return json.fromJson(value, VARIANTS);
    }
}
