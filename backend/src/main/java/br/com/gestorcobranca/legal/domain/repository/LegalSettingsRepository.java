package br.com.gestorcobranca.legal.domain.repository;

import br.com.gestorcobranca.legal.domain.model.LegalSettings;

import java.util.Optional;

/** Parâmetros do jurídico: no máximo um registro por tenant (RLS limita ao tenant corrente). */
public interface LegalSettingsRepository {

    LegalSettings save(LegalSettings settings);

    Optional<LegalSettings> findFirstByOrderByCreatedAtAsc();
}
