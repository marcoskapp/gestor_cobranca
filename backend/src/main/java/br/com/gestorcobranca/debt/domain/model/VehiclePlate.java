package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Placa de veículo brasileira, normalizada em 7 caracteres maiúsculos sem separadores. Aceita o
 * padrão antigo ({@code ABC1234}, exibido como {@code ABC-1234}) e o padrão Mercosul
 * ({@code ABC1D23}).
 */
public record VehiclePlate(String value) {

    private static final Pattern LEGACY = Pattern.compile("^[A-Z]{3}[0-9]{4}$");
    private static final Pattern MERCOSUL = Pattern.compile("^[A-Z]{3}[0-9][A-Z][0-9]{2}$");

    public VehiclePlate {
        Objects.requireNonNull(value, "value");
        Guard.require(LEGACY.matcher(value).matches() || MERCOSUL.matcher(value).matches(), "collateral.plate_invalid",
                "Placa inválida: use o padrão antigo (ABC-1234) ou Mercosul (ABC1D23)");
    }

    /** Valida e normaliza a placa informada com ou sem hífen/espaços, em qualquer caixa. */
    public static VehiclePlate parse(String raw) {
        Guard.require(raw != null && !raw.isBlank(), "collateral.plate_invalid", "Placa obrigatória");
        return new VehiclePlate(raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT));
    }

    public boolean isMercosul() {
        return MERCOSUL.matcher(value).matches();
    }

    /** Forma de exibição: {@code ABC-1234} (antiga) ou {@code ABC1D23} (Mercosul). */
    public String formatted() {
        return isMercosul() ? value : value.substring(0, 3) + "-" + value.substring(3);
    }
}
