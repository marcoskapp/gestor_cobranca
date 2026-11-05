package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.domain.model.DisputeTemplate;
import br.com.gestorcobranca.collection.domain.repository.DisputeTemplateRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface DisputeTemplateJpaRepository extends JpaRepository<DisputeTemplate, UUID>, DisputeTemplateRepository {
}
