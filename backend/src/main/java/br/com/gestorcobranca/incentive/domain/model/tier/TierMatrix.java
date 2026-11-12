package br.com.gestorcobranca.incentive.domain.model.tier;

import br.com.gestorcobranca.shared.domain.Guard;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Matriz qualidade × eficiência → nível (distribuicao-por-desempenho.md §3.2), parametrizável. As 16 células são
 * obrigatórias e só aceitam A–D. Representação externa (API e banco): {@code {"Q4": {"Q4": "A", "Q3": "A", ...}, ...}},
 * com a qualidade na primeira chave e a eficiência na segunda.
 */
public final class TierMatrix {

    private static final Set<String> KEYS = Set.of("Q1", "Q2", "Q3", "Q4");

    /** [qualidade 1–4][eficiência 1–4], índice 0 = Q1. */
    private final Tier[][] cells;

    private TierMatrix(Tier[][] cells) {
        this.cells = cells;
    }

    /** Padrão do desenho (§3.2). */
    public static TierMatrix standard() {
        Map<String, Map<String, String>> map = new LinkedHashMap<>();
        map.put("Q4", row("A", "A", "B", "C"));
        map.put("Q3", row("A", "B", "B", "C"));
        map.put("Q2", row("B", "C", "C", "D"));
        map.put("Q1", row("C", "C", "D", "D"));
        return of(map);
    }

    /** Valida (16 células, só A–D) e monta a matriz. */
    public static TierMatrix of(Map<String, Map<String, String>> map) {
        Guard.require(map != null && map.size() == 4 && map.keySet().equals(KEYS), "tier_settings.matrix_invalid",
                "A matriz precisa das 4 linhas de qualidade (Q1 a Q4)");
        Tier[][] cells = new Tier[4][4];
        for (Map.Entry<String, Map<String, String>> row : map.entrySet()) {
            Map<String, String> columns = row.getValue();
            Guard.require(columns != null && columns.size() == 4 && columns.keySet().equals(KEYS),
                    "tier_settings.matrix_invalid", "Cada linha da matriz precisa das 4 colunas de eficiência (Q1 a Q4)");
            for (Map.Entry<String, String> cell : columns.entrySet()) {
                cells[index(row.getKey())][index(cell.getKey())] = Tier.parse(cell.getValue(), "tier_settings.matrix_invalid");
            }
        }
        return new TierMatrix(cells);
    }

    /** Nível da célula (quartis 1–4). */
    public Tier tierFor(int qualityQuartile, int efficiencyQuartile) {
        if (qualityQuartile < 1 || qualityQuartile > 4 || efficiencyQuartile < 1 || efficiencyQuartile > 4) {
            throw new IllegalArgumentException("Quartil fora de 1–4");
        }
        return cells[qualityQuartile - 1][efficiencyQuartile - 1];
    }

    /** Representação externa, da qualidade Q4 para Q1 e, em cada linha, da eficiência Q4 para Q1. */
    public Map<String, Map<String, String>> toMap() {
        Map<String, Map<String, String>> map = new LinkedHashMap<>();
        for (int q = 4; q >= 1; q--) {
            Map<String, String> columns = new LinkedHashMap<>();
            for (int e = 4; e >= 1; e--) {
                columns.put("Q" + e, tierFor(q, e).name());
            }
            map.put("Q" + q, columns);
        }
        return map;
    }

    private static int index(String key) {
        return key.charAt(1) - '1';
    }

    private static Map<String, String> row(String q4, String q3, String q2, String q1) {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("Q4", q4);
        columns.put("Q3", q3);
        columns.put("Q2", q2);
        columns.put("Q1", q1);
        return columns;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TierMatrix other && toMap().equals(other.toMap());
    }

    @Override
    public int hashCode() {
        return toMap().hashCode();
    }

    @Override
    public String toString() {
        return toMap().toString();
    }
}
