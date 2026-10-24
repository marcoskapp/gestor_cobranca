package br.com.gestorcobranca.scoring.domain.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Valores brutos das features de um caso numa data de referência ({@code null} = ausente, ex.:
 * nunca houve contato efetivo). Imutável.
 */
public final class FeatureVector {

    private final EnumMap<Feature, Double> values;

    private FeatureVector(EnumMap<Feature, Double> values) {
        this.values = values;
    }

    public static FeatureVector of(Map<Feature, Double> values) {
        EnumMap<Feature, Double> copy = new EnumMap<>(Feature.class);
        for (Feature f : Feature.values()) {
            Double v = values.get(f);
            copy.put(f, v == null || v.isNaN() || v.isInfinite() ? null : v);
        }
        return new FeatureVector(copy);
    }

    /** Reconstrói a partir do JSON persistido (chaves = nomes do catálogo; desconhecidas são ignoradas). */
    public static FeatureVector fromNames(Map<String, ?> values) {
        EnumMap<Feature, Double> map = new EnumMap<>(Feature.class);
        for (Feature f : Feature.values()) {
            Object v = values.get(f.name());
            map.put(f, v instanceof Number n ? n.doubleValue() : null);
        }
        return of(map);
    }

    public Double get(Feature feature) {
        return values.get(feature);
    }

    /** Mapa ordenado pelo catálogo, com nomes como chave (persistência e API). */
    public Map<String, Double> toNames() {
        Map<String, Double> out = new LinkedHashMap<>();
        values.forEach((k, v) -> out.put(k.name(), v));
        return Collections.unmodifiableMap(out);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FeatureVector other && values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
