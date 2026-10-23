package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.negotiation.domain.model.agreement.Agreement.BucketPosting;
import br.com.gestorcobranca.negotiation.domain.model.agreement.LedgerPart;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.json.JsonCodec;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Formas persistidas (jsonb) das alocações de um pagamento de acordo. */
final class LedgerLines {

    /** Parte de um pagamento num componente de um título e o encargo reconhecido antes do lançamento. */
    record Line(UUID titleId, String part, BigDecimal payment, BigDecimal charge, BigDecimal settlementCharge,
                BigDecimal discount) {
    }

    private static final TypeReference<List<Line>> LINES = new TypeReference<>() {
    };
    private static final TypeReference<LinkedHashMap<UUID, BigDecimal>> ALLOCATIONS = new TypeReference<>() {
    };

    private LedgerLines() {
    }

    static String postingsJson(JsonCodec json, List<BucketPosting> postings) {
        return json.toJson(postings.stream().map(p -> new Line(p.titleId(), p.part().name(), p.payment().amount(),
                p.charge().amount(), p.settlementCharge().amount(), p.discount().amount())).toList());
    }

    static List<BucketPosting> postings(JsonCodec json, String value) {
        return json.fromJson(value, LINES).stream().map(l -> new BucketPosting(l.titleId(), LedgerPart.valueOf(l.part()),
                Money.of(l.payment()), Money.of(l.charge()), Money.of(l.settlementCharge()), Money.of(l.discount())))
                .toList();
    }

    static String allocationsJson(JsonCodec json, Map<UUID, Money> allocations) {
        Map<UUID, BigDecimal> plain = new LinkedHashMap<>();
        allocations.forEach((k, v) -> plain.put(k, v.amount()));
        return json.toJson(plain);
    }

    static Map<UUID, Money> allocations(JsonCodec json, String value) {
        Map<UUID, Money> result = new LinkedHashMap<>();
        json.fromJson(value, ALLOCATIONS).forEach((k, v) -> result.put(k, Money.of(v)));
        return result;
    }
}
