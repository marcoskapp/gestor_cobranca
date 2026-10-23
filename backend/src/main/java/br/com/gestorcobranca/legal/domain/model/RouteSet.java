package br.com.gestorcobranca.legal.domain.model;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Conversão de conjuntos de vias para a coluna textual (nomes separados por vírgula, ordenados). */
final class RouteSet {

    private RouteSet() {
    }

    static String join(Collection<LegalRoute> routes) {
        return routes == null || routes.isEmpty() ? null
                : routes.stream().distinct().sorted().map(Enum::name).collect(Collectors.joining(","));
    }

    static Set<LegalRoute> split(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<LegalRoute> set = EnumSet.noneOf(LegalRoute.class);
        Arrays.stream(text.split(",")).map(LegalRoute::valueOf).forEach(set::add);
        return Set.copyOf(set);
    }
}
