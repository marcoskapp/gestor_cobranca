package br.com.gestorcobranca.ingestion.domain.model;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;

/**
 * Situação do título informada pelo credor na carga (coluna {@code situacao}, opcional). Ausente
 * equivale a {@link #ACTIVE}. O tratamento de cada situação está em
 * {@link br.com.gestorcobranca.ingestion.domain.service.TitleSituationPolicy}.
 */
public enum TitleSituation {
    /** Em aberto, em cobrança. */
    ACTIVE,
    /** Pago diretamente ao credor. */
    PAID,
    /** Cobrança suspensa pelo credor. */
    SUSPENDED,
    /** Contestado pelo devedor junto ao credor. */
    DISPUTED,
    /** Cancelado/baixado pelo credor. */
    CANCELLED;

    /** Aceita os termos usuais (sem diferenciar acentos e maiúsculas); vazio se não reconhecido. */
    public static Optional<TitleSituation> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = Normalizer.normalize(raw.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT).replaceAll("[\\s_-]+", " ");
        return Optional.ofNullable(switch (value) {
            case "ATIVO", "ABERTO", "EM ABERTO", "ACTIVE", "OPEN" -> ACTIVE;
            case "PAGO", "QUITADO", "LIQUIDADO", "PAID" -> PAID;
            case "SUSPENSO", "SUSPENDED" -> SUSPENDED;
            case "CONTESTADO", "EM CONTESTACAO", "CONTESTACAO", "DISPUTED" -> DISPUTED;
            case "CANCELADO", "BAIXADO", "CANCELLED" -> CANCELLED;
            default -> null;
        });
    }

    /** O credor considera a dívida encerrada: o título não deve ser criado nem continuar em cobrança. */
    public boolean closedAtCreditor() {
        return this == PAID || this == CANCELLED;
    }

    /** O credor pede que a cobrança fique parada. */
    public boolean holdsCollection() {
        return this == SUSPENDED || this == DISPUTED;
    }
}
