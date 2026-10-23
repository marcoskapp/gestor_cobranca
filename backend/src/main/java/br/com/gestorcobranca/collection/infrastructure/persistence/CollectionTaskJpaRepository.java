package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.domain.model.CollectionTask;
import br.com.gestorcobranca.collection.domain.model.TaskStatus;
import br.com.gestorcobranca.collection.domain.repository.CollectionTaskRepository;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface CollectionTaskJpaRepository extends JpaRepository<CollectionTask, UUID>, CollectionTaskRepository {

    @Override
    @Query("""
            select t from CollectionTask t
             where t.assigneeUserId = :userId and t.dueAt >= :from and t.dueAt < :to and t.status in :statuses
             order by t.dueAt
            """)
    Page<CollectionTask> findAgenda(UUID userId, Instant from, Instant to, Collection<TaskStatus> statuses,
                                    Pageable pageable);

    @Override
    default List<CollectionTask> findDueForUser(UUID userId, Instant now, int limit) {
        return dueForUser(userId, now, Limit.of(limit));
    }

    @Query("""
            select t from CollectionTask t
             where t.assigneeUserId = :userId and t.dueAt <= :now
               and t.type = br.com.gestorcobranca.collection.domain.model.TaskType.CALLBACK
               and t.status in (br.com.gestorcobranca.collection.domain.model.TaskStatus.PENDING,
                                br.com.gestorcobranca.collection.domain.model.TaskStatus.MISSED)
             order by t.dueAt
            """)
    List<CollectionTask> dueForUser(UUID userId, Instant now, Limit limit);

    @Override
    default List<CollectionTask> findPendingDueBefore(Instant before, int limit) {
        return pendingDueBefore(before, Limit.of(limit));
    }

    @Query("""
            select t from CollectionTask t
             where t.status = br.com.gestorcobranca.collection.domain.model.TaskStatus.PENDING and t.dueAt < :before
             order by t.dueAt
            """)
    List<CollectionTask> pendingDueBefore(Instant before, Limit limit);
}
