package br.com.gestorcobranca.compliance.domain.repository;

import br.com.gestorcobranca.compliance.domain.model.RetentionCategory;
import br.com.gestorcobranca.compliance.domain.model.RetentionPolicy;

import java.util.List;
import java.util.Optional;

public interface RetentionPolicyRepository {

    RetentionPolicy save(RetentionPolicy policy);

    Optional<RetentionPolicy> findByCategory(RetentionCategory category);

    List<RetentionPolicy> findAllByOrderByCategoryAsc();

    /** Só para voltar ao padrão do sistema (categoria com padrão): a política da assessoria deixa de existir. */
    void delete(RetentionPolicy policy);
}
