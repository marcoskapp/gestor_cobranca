package br.com.gestorcobranca.negotiation.infrastructure.persistence;

import br.com.gestorcobranca.negotiation.application.port.out.MonetaryIndexProvider;
import br.com.gestorcobranca.negotiation.domain.model.calculation.IndexSeries;
import br.com.gestorcobranca.negotiation.domain.model.index.MonetaryIndexRate;
import br.com.gestorcobranca.negotiation.domain.repository.MonetaryIndexRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@link MonetaryIndexProvider} sobre a tabela manual do tenant ({@code negotiation.monetary_index_rate},
 * isolada por RLS). Substituível por um adaptador de fonte oficial sem mudar o cálculo.
 */
@Component
class ManualMonetaryIndexProvider implements MonetaryIndexProvider {

    private final MonetaryIndexRepository indexes;

    ManualMonetaryIndexProvider(MonetaryIndexRepository indexes) {
        this.indexes = indexes;
    }

    @Override
    public Optional<IndexSeries> series(String indexCode) {
        return indexes.findByCode(indexCode).map(index -> new IndexSeries(index.getCode(),
                indexes.findRates(index.getId()).stream().collect(Collectors.toMap(MonetaryIndexRate::getCompetence,
                        MonetaryIndexRate::getRatePercent, (a, b) -> b))));
    }
}
