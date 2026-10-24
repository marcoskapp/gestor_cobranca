package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.TitleAmountStatisticsPort;
import br.com.gestorcobranca.debt.application.port.out.TitleAmountStatisticsReader;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
class TitleAmountStatisticsService implements TitleAmountStatisticsPort {

    private static final int MAX_PRODUCTS = 50;

    private final TitleAmountStatisticsReader reader;
    private final AccessScope accessScope;

    TitleAmountStatisticsService(TitleAmountStatisticsReader reader, AccessScope accessScope) {
        this.reader = reader;
        this.accessScope = accessScope;
    }

    @Override
    public AmountStatistics statistics(UUID creditorId, UUID portfolioId, Collection<String> products) {
        accessScope.requireCreditor(creditorId);
        List<String> wanted = products == null ? List.of()
                : products.stream().filter(Objects::nonNull).distinct().limit(MAX_PRODUCTS).toList();
        Map<String, Distribution> byProduct = wanted.isEmpty() || portfolioId == null ? Map.of()
                : reader.byProduct(creditorId, portfolioId, wanted);
        return new AmountStatistics(reader.distribution(creditorId, null),
                portfolioId == null ? Distribution.EMPTY : reader.distribution(creditorId, portfolioId), byProduct);
    }
}
