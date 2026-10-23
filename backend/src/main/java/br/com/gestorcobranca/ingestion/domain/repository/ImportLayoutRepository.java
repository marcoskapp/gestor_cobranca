package br.com.gestorcobranca.ingestion.domain.repository;

import br.com.gestorcobranca.ingestion.domain.model.ImportLayout;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ImportLayoutRepository {

    ImportLayout save(ImportLayout layout);

    /**
     * Grava e descarrega imediatamente. Usado ao desativar a versão anterior antes de inserir a
     * nova: o Hibernate executa inserções antes de atualizações no flush, e o índice único de
     * versão ativa recusaria a nova versão se a anterior ainda estivesse ativa no banco.
     */
    ImportLayout saveAndFlush(ImportLayout layout);

    Optional<ImportLayout> findById(UUID id);

    Optional<ImportLayout> findByCreditorIdAndCodeAndActiveTrue(UUID creditorId, String code);

    /** Maior versão já publicada do código no credor (0 se nenhuma). */
    int maxVersion(UUID creditorId, String code);

    /**
     * @param creditorIds credores visíveis; {@code null} = todos
     */
    List<ImportLayout> list(Collection<UUID> creditorIds, UUID creditorId, boolean includeInactive);
}
