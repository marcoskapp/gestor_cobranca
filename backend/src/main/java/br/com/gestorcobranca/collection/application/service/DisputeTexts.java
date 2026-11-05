package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputedTitle;
import br.com.gestorcobranca.collection.domain.model.DisputeOutcome;
import br.com.gestorcobranca.collection.domain.model.DisputeType;
import br.com.gestorcobranca.shared.config.TimeConfig;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Textos dos e-mails e mensagens das contestações (rótulos em português, datas e valores no formato brasileiro). */
final class DisputeTexts {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private DisputeTexts() {
    }

    /** Protocolo exibido ao credor e ao devedor: os 8 primeiros caracteres do id, em maiúsculas. */
    static String protocol(UUID disputeId) {
        return disputeId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    static String type(DisputeType type) {
        return switch (type) {
            case NOT_RECOGNIZED -> "dívida não reconhecida";
            case ALREADY_PAID -> "alegação de pagamento";
            case WRONG_AMOUNT -> "valor incorreto";
            case FRAUD -> "suspeita de fraude";
            case PRESCRIBED -> "alegação de prescrição";
            case PRODUCT_OR_SERVICE -> "produto ou serviço";
            case OTHER -> "outro motivo";
        };
    }

    static String outcome(DisputeOutcome outcome) {
        return switch (outcome) {
            case UPHELD -> "procedente";
            case PARTIALLY_UPHELD -> "parcialmente procedente";
            case REJECTED -> "improcedente";
        };
    }

    static String money(BigDecimal value) {
        NumberFormat format = NumberFormat.getCurrencyInstance(PT_BR);
        return format.format(value == null ? BigDecimal.ZERO : value).replace(' ', ' ');
    }

    static String date(LocalDate date) {
        return date == null ? "" : DATE.format(date);
    }

    static String date(Instant instant) {
        return instant == null ? "" : DATE.format(LocalDate.ofInstant(instant, TimeConfig.BUSINESS_ZONE));
    }

    /** Uma linha por título: número no credor, parcela, vencimento, valor original e saldo. */
    static String titles(List<DisputedTitle> titles, boolean withBalance) {
        StringBuilder out = new StringBuilder();
        for (DisputedTitle t : titles) {
            out.append("- Título ").append(t.externalId());
            if (t.installmentNumber() != null) {
                out.append(", parcela ").append(t.installmentNumber());
            }
            out.append(", vencimento ").append(date(t.dueDate()))
                    .append(", valor original ").append(money(t.principalAmount()));
            if (withBalance) {
                out.append(", saldo ").append(money(t.openBalance()));
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** Primeiro nome do devedor (uma palavra), para a saudação da resposta. */
    static String firstName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String first = name.trim().split("\\s+")[0];
        return first.substring(0, 1).toUpperCase(PT_BR) + first.substring(1).toLowerCase(PT_BR);
    }
}
