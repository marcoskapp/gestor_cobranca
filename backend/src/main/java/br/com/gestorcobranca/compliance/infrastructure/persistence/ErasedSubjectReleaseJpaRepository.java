package br.com.gestorcobranca.compliance.infrastructure.persistence;

import br.com.gestorcobranca.compliance.domain.model.ErasedSubjectRelease;
import br.com.gestorcobranca.compliance.domain.repository.ErasedSubjectReleaseRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface ErasedSubjectReleaseJpaRepository extends JpaRepository<ErasedSubjectRelease, UUID>,
        ErasedSubjectReleaseRepository {
}
