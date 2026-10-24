package br.com.gestorcobranca.assistant.infrastructure.persistence;

import br.com.gestorcobranca.assistant.domain.model.TurnReview;
import br.com.gestorcobranca.assistant.domain.repository.TurnReviewRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface TurnReviewJpaRepository extends JpaRepository<TurnReview, UUID>, TurnReviewRepository {
}
