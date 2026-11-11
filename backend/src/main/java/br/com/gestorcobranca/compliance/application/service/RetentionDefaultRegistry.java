package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.application.port.spi.RetentionDefaults;
import br.com.gestorcobranca.compliance.application.port.spi.RetentionDefaults.RetentionDefault;
import br.com.gestorcobranca.compliance.domain.model.RetentionCategory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.stream.Collectors;

/**
 * Prazos padrão do sistema informados pelos módulos donos ({@link RetentionDefaults}). Conferido na subida: toda
 * categoria com padrão do sistema tem exatamente um prazo, dentro dos limites da categoria; categoria sem padrão não
 * recebe prazo. Configuração inconsistente impede a aplicação de subir (nunca descartar com prazo inválido).
 */
@Component
class RetentionDefaultRegistry {

    private final Map<RetentionCategory, Integer> defaults;

    RetentionDefaultRegistry(List<RetentionDefaults> sources) {
        Map<RetentionCategory, Integer> collected = new EnumMap<>(RetentionCategory.class);
        for (RetentionDefaults source : sources) {
            for (RetentionDefault d : source.retentionDefaults()) {
                RetentionCategory category = parse(d.category());
                if (!category.hasSystemDefault()) {
                    throw new IllegalStateException("Categoria de retenção %s não aceita padrão do sistema".formatted(category));
                }
                if (!category.accepts(d.amount())) {
                    throw new IllegalStateException("Prazo padrão de retenção %s fora dos limites (%d–%d %s): %d".formatted(
                            category, category.minAmount(), category.maxAmount(), category.unit(), d.amount()));
                }
                if (d.requiredMinimum() > category.minAmount()) {
                    throw new IllegalStateException(("Retenção %s: o módulo dono precisa de pelo menos %d %s pela "
                            + "configuração dele, mas a tela de Retenção aceita a partir de %d; reduza a configuração do "
                            + "módulo (ex.: app.scoring.fact-lookback-days) para no máximo %d").formatted(category,
                            d.requiredMinimum(), category.unit(), category.minAmount(), category.minAmount()));
                }
                if (collected.putIfAbsent(category, d.amount()) != null) {
                    throw new IllegalStateException("Prazo padrão de retenção %s informado mais de uma vez".formatted(category));
                }
            }
        }
        String missing = Arrays.stream(RetentionCategory.values())
                .filter(c -> c.hasSystemDefault() && !collected.containsKey(c))
                .map(Enum::name).collect(Collectors.joining(", "));
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Categorias de retenção sem prazo padrão do sistema: " + missing);
        }
        this.defaults = Map.copyOf(collected);
    }

    /** Prazo padrão do sistema, na unidade da categoria (vazio para categoria sem padrão). */
    OptionalInt defaultFor(RetentionCategory category) {
        Integer amount = defaults.get(category);
        return amount == null ? OptionalInt.empty() : OptionalInt.of(amount);
    }

    private static RetentionCategory parse(String category) {
        try {
            return RetentionCategory.valueOf(category);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException("Categoria de retenção desconhecida no prazo padrão: " + category, e);
        }
    }
}
