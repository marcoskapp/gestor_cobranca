package br.com.gestorcobranca.analytics.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;

import java.util.Locale;
import java.util.Set;

/**
 * Dimensões de agrupamento dos relatórios (lista fixa: o SQL nunca recebe nome de coluna do
 * cliente). Cada relatório aceita um subconjunto, validado por {@link #parse(String, Set)}.
 */
public enum Dimension {
    NONE, CREDITOR, PORTFOLIO, OPERATOR, TEAM, CHANNEL, ORIGIN, DELAY_BUCKET, DAY, WEEK, MONTH;

    /** Agrupamento por pessoa (sujeito ao escopo próprio/equipe/tudo). */
    public boolean byPerson() {
        return this == OPERATOR || this == TEAM;
    }

    public boolean temporal() {
        return this == DAY || this == WEEK || this == MONTH;
    }

    public static Dimension parse(String value, Set<Dimension> allowed) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        Dimension d;
        try {
            d = valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            d = null;
        }
        Guard.require(d != null && allowed.contains(d), "analytics.group_by_invalid",
                "Agrupamento não disponível neste relatório: " + value);
        return d;
    }
}
