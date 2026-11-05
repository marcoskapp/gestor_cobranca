package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.domain.model.DisputeMail;
import br.com.gestorcobranca.collection.domain.repository.DisputeMailRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface DisputeMailJpaRepository extends JpaRepository<DisputeMail, UUID>, DisputeMailRepository {
}
