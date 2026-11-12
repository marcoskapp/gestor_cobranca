package br.com.gestorcobranca.collection.domain.service;

import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.model.CollectionQueue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Roteamento de casos para filas: a primeira fila ativa, em ordem de prioridade (empate pelo
 * código), cujos critérios o caso satisfaz. Casos encerrados não ficam em fila.
 */
public final class QueueRouter {

    private static final Comparator<CollectionQueue> ORDER =
            Comparator.comparingInt(CollectionQueue::getPriority).thenComparing(CollectionQueue::getCode);

    private QueueRouter() {
    }

    /**
     * @param today data de referência do atraso (fuso de operação)
     * @param now   instante de referência do contato novo (janela de novidade)
     */
    public static Optional<CollectionQueue> route(CollectionCase c, List<CollectionQueue> queues, LocalDate today,
                                                  Instant now) {
        if (!c.getStatus().isActive()) {
            return Optional.empty();
        }
        return queues.stream()
                .filter(CollectionQueue::isActive)
                .sorted(ORDER)
                .filter(q -> q.matches(c, today, now))
                .findFirst();
    }
}
