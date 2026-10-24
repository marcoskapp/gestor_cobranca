package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normaliza os registros recebidos pela API antes de guardá-los: strings seguem como texto (e passam pela análise do
 * formato por coluna), números viram decimal exato e booleanos viram texto. Objetos e listas são recusados.
 * <p>
 * A camada HTTP lê números com fração como {@code double}: até 15 dígitos significativos a leitura é exata (a
 * representação mais curta do {@code double} devolve o número enviado); acima disso o número é recusado — envie-o
 * como texto.
 */
final class RecordValues {

    static final int MAX_SIGNIFICANT_DIGITS = 15;

    private RecordValues() {
    }

    static List<Map<String, Object>> canonical(List<Map<String, Object>> records) {
        List<Map<String, Object>> out = new ArrayList<>(records.size());
        for (int i = 0; i < records.size(); i++) {
            Map<String, Object> record = records.get(i);
            Map<String, Object> clean = new LinkedHashMap<>();
            if (record != null) {
                for (Map.Entry<String, Object> e : record.entrySet()) {
                    Object value = value(i + 1, e.getKey(), e.getValue());
                    if (value != null) {
                        clean.put(e.getKey(), value);
                    }
                }
            }
            out.add(clean);
        }
        return out;
    }

    private static Object value(int position, String key, Object value) {
        return switch (value) {
            case null -> null;
            case String s -> s;
            case Boolean b -> b.toString();
            case Integer n -> BigDecimal.valueOf(n);
            case Long n -> BigDecimal.valueOf(n);
            case BigInteger n -> new BigDecimal(n);
            case BigDecimal n -> n;
            case Double d -> fraction(position, key, d);
            case Float f -> fraction(position, key, f.doubleValue());
            case Number n -> new BigDecimal(n.toString());
            default -> throw new BusinessRuleException("import.record_value_invalid",
                    "Registro %d, campo %s: envie texto, número ou booleano".formatted(position, key));
        };
    }

    private static BigDecimal fraction(int position, String key, double value) {
        if (!Double.isFinite(value)) {
            throw new BusinessRuleException("import.record_value_invalid",
                    "Registro %d, campo %s: número inválido".formatted(position, key));
        }
        BigDecimal exact = BigDecimal.valueOf(value);
        if (exact.stripTrailingZeros().precision() > MAX_SIGNIFICANT_DIGITS) {
            throw new BusinessRuleException("import.record_number_precision",
                    "Registro %d, campo %s: número com mais de %d dígitos significativos; envie como texto"
                            .formatted(position, key, MAX_SIGNIFICANT_DIGITS));
        }
        return exact;
    }
}
