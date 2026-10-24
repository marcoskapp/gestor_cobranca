package br.com.gestorcobranca.scoring.domain.repository;

import br.com.gestorcobranca.scoring.domain.model.ScoreReview;

import java.util.List;
import java.util.UUID;

public interface ScoreReviewRepository {

    ScoreReview save(ScoreReview review);

    List<ScoreReview> findByCaseIdOrderByReviewedAtDesc(UUID caseId);
}
