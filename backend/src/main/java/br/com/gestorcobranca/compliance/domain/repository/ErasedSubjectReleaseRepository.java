package br.com.gestorcobranca.compliance.domain.repository;

import br.com.gestorcobranca.compliance.domain.model.ErasedSubjectRelease;

import java.util.Optional;
import java.util.UUID;

public interface ErasedSubjectReleaseRepository {

    ErasedSubjectRelease save(ErasedSubjectRelease release);

    Optional<ErasedSubjectRelease> findByRequestId(UUID requestId);
}
