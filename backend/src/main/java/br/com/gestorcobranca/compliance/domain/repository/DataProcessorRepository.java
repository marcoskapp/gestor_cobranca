package br.com.gestorcobranca.compliance.domain.repository;

import br.com.gestorcobranca.compliance.domain.model.DataProcessor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DataProcessorRepository {

    DataProcessor save(DataProcessor processor);

    Optional<DataProcessor> findById(UUID id);

    boolean existsByProviderKey(String providerKey);

    List<DataProcessor> findAllByOrderByNameAsc();

    List<DataProcessor> findByProviderKeyIn(Collection<String> providerKeys);

    /** Ativos com a chave no prefixo (ex.: {@link DataProcessor#CUSTOM_KEY_PREFIX}); a vigência é conferida por quem usa. */
    List<DataProcessor> findByProviderKeyStartingWithAndActiveTrue(String prefix);
}
