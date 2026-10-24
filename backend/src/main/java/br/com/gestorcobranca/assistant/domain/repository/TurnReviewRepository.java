package br.com.gestorcobranca.assistant.domain.repository;

import br.com.gestorcobranca.assistant.domain.model.ReviewStatus;
import br.com.gestorcobranca.assistant.domain.model.TurnReview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TurnReviewRepository {

    TurnReview save(TurnReview review);

    Optional<TurnReview> findById(UUID id);

    Optional<TurnReview> findByTurnId(UUID turnId);

    List<TurnReview> findByDebtorId(UUID debtorId);

    Page<TurnReview> findByStatusAndCreditorIdIn(ReviewStatus status, Collection<UUID> creditorIds, Pageable pageable);

    Page<TurnReview> findByStatus(ReviewStatus status, Pageable pageable);
}
