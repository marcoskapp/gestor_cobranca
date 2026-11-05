package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.domain.model.DisputeTemplateKind;
import br.com.gestorcobranca.collection.domain.repository.DisputeTemplateRepository;
import br.com.gestorcobranca.collection.domain.service.DisputeTemplates;
import org.springframework.stereotype.Component;

/** Modelo vigente: a última versão gravada pela assessoria ou, sem ela, o padrão do sistema (versão 0). */
@Component
class DisputeTemplateProvider {

    private final DisputeTemplateRepository templates;

    DisputeTemplateProvider(DisputeTemplateRepository templates) {
        this.templates = templates;
    }

    record Current(String key, int version, String subject, String body) {
    }

    Current current(DisputeTemplateKind kind, String key) {
        return templates.findFirstByKindAndTemplateKeyOrderByTemplateVersionDesc(kind, key)
                .map(t -> new Current(key, t.getTemplateVersion(), t.getSubject(), t.getBody()))
                .orElseGet(() -> {
                    DisputeTemplates.Text text = DisputeTemplates.defaultFor(kind, key);
                    return new Current(key, 0, text.subject(), text.body());
                });
    }
}
