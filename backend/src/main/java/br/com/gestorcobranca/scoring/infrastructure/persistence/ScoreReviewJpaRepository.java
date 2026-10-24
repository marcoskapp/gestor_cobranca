package br.com.gestorcobranca.scoring.infrastructure.persistence;

import br.com.gestorcobranca.scoring.domain.model.ScoreReview;
import br.com.gestorcobranca.scoring.domain.repository.ScoreReviewRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface ScoreReviewJpaRepository extends JpaRepository<ScoreReview, UUID>, ScoreReviewRepository {

    @Override
    List<ScoreReview> findByCaseIdOrderByReviewedAtDesc(UUID caseId);
}
