package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.Tabulation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TabulationRepository {

    Tabulation save(Tabulation tabulation);

    Optional<Tabulation> findById(UUID id);

    Optional<Tabulation> findByCode(String code);

    List<Tabulation> findAllByOrderByDisplayOrderAscCodeAsc();
}
