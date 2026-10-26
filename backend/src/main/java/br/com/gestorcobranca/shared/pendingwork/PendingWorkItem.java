package br.com.gestorcobranca.shared.pendingwork;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Uma pendência agregada por tipo, com o destino na interface onde a ação é executada.
 *
 * @param type        código estável {@code <modulo>.<pendencia>} (ex.: {@code payment.external_review})
 * @param label       rótulo curto da pendência (pt-BR, plural), ex.: "Pagamentos informados para aprovar"
 * @param hint        orientação da ação (opcional)
 * @param count       contagens, item mais antigo e prazo mais próximo
 * @param route       rota do frontend (começa com {@code /}); com um único item, a do próprio item
 * @param queryParams filtro da tela de destino na query string (apenas códigos e ids, nunca dado pessoal)
 */
public record PendingWorkItem(String type, String label, String hint, PendingWorkCount count, String route,
                              Map<String, String> queryParams) {

    private static final Pattern TYPE = Pattern.compile("[a-z]+(\\.[a-z_]+)+");
    private static final Pattern PARAM = Pattern.compile("[a-z][a-zA-Z]{0,30}");
    private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9_,:-]{1,80}");

    public PendingWorkItem {
        Objects.requireNonNull(type, "type");
        if (!TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("Tipo de pendência inválido: " + type);
        }
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(count, "count");
        Objects.requireNonNull(route, "route");
        if (!route.startsWith("/") || route.contains("?") || route.contains("#")) {
            throw new IllegalArgumentException("Rota de destino inválida: " + route);
        }
        Map<String, String> copy = new LinkedHashMap<>();
        if (queryParams != null) {
            queryParams.forEach((k, v) -> {
                if (!PARAM.matcher(k).matches() || v == null || !VALUE.matcher(v).matches()) {
                    throw new IllegalArgumentException("Filtro de destino inválido: " + k);
                }
                copy.put(k, v);
            });
        }
        queryParams = Collections.unmodifiableMap(copy);
    }

    public PendingWorkSeverity severity() {
        return count.severity();
    }

    public static Builder of(String type, String label, PendingWorkCount count) {
        return new Builder(type, label, count);
    }

    /** Montagem do destino: lista filtrada ou, quando há um único item, o próprio item. */
    public static final class Builder {

        private final String type;
        private final String label;
        private final PendingWorkCount count;
        private String hint;
        private String route;
        private final Map<String, String> params = new LinkedHashMap<>();
        private Function<UUID, String> single;

        private Builder(String type, String label, PendingWorkCount count) {
            this.type = type;
            this.label = label;
            this.count = count;
        }

        public Builder hint(String hint) {
            this.hint = hint;
            return this;
        }

        public Builder route(String route) {
            this.route = route;
            return this;
        }

        public Builder param(String key, String value) {
            if (value != null) {
                params.put(key, value);
            }
            return this;
        }

        /** Rota do item quando a contagem tem um único item com id conhecido (sem filtros). */
        public Builder singleRoute(Function<UUID, String> single) {
            this.single = single;
            return this;
        }

        public PendingWorkItem build() {
            if (single != null && count.singleId() != null) {
                return new PendingWorkItem(type, label, hint, count, single.apply(count.singleId()), Map.of());
            }
            return new PendingWorkItem(type, label, hint, count, route, params);
        }
    }
}
