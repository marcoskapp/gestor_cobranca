package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.DisputeTemplate;
import br.com.gestorcobranca.collection.domain.model.DisputeTemplateKind;

import java.util.List;
import java.util.Optional;

public interface DisputeTemplateRepository {

    DisputeTemplate save(DisputeTemplate template);

    /** Versão vigente (a de maior número) do modelo. */
    Optional<DisputeTemplate> findFirstByKindAndTemplateKeyOrderByTemplateVersionDesc(DisputeTemplateKind kind,
                                                                                    String templateKey);

    List<DisputeTemplate> findByKindAndTemplateKeyOrderByTemplateVersionDesc(DisputeTemplateKind kind, String templateKey);

    List<DisputeTemplate> findAll();
}
