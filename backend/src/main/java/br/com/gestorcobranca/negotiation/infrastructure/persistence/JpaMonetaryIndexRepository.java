package br.com.gestorcobranca.negotiation.infrastructure.persistence;

import br.com.gestorcobranca.negotiation.domain.model.index.MonetaryIndex;
import br.com.gestorcobranca.negotiation.domain.model.index.MonetaryIndexRate;
import br.com.gestorcobranca.negotiation.domain.repository.MonetaryIndexRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaMonetaryIndexRepository implements MonetaryIndexRepository {

    private final MonetaryIndexJpa indexes;
    private final MonetaryIndexRateJpa rates;

    JpaMonetaryIndexRepository(MonetaryIndexJpa indexes, MonetaryIndexRateJpa rates) {
        this.indexes = indexes;
        this.rates = rates;
    }

    @Override
    public MonetaryIndex save(MonetaryIndex index) {
        return indexes.save(index);
    }

    @Override
    public Optional<MonetaryIndex> findByCode(String code) {
        return indexes.findByCode(code);
    }

    @Override
    public List<MonetaryIndex> findAllIndexes() {
        return indexes.findAllByOrderByCode();
    }

    @Override
    public boolean existsByCode(String code) {
        return indexes.existsByCode(code);
    }

    @Override
    public MonetaryIndexRate saveRate(MonetaryIndexRate rate) {
        return rates.save(rate);
    }

    @Override
    public List<MonetaryIndexRate> findRates(UUID indexId) {
        return rates.findByIndex(indexId);
    }
}
