package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Chassi (VIN, ISO 3779): 17 caracteres alfanuméricos maiúsculos, sem as letras I, O e Q. O
 * dígito verificador da 9ª posição não é validado porque só é obrigatório na América do Norte;
 * chassis nacionais válidos podem não segui-lo.
 */
public record Vin(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[A-HJ-NPR-Z0-9]{17}$");

    public Vin {
        Objects.requireNonNull(value, "value");
        Guard.require(FORMAT.matcher(value).matches(), "collateral.chassis_invalid",
                "Chassi deve ter 17 caracteres alfanuméricos, sem I, O ou Q");
    }

    public static Vin parse(String raw) {
        Guard.require(raw != null && !raw.isBlank(), "collateral.chassis_invalid", "Chassi obrigatório");
        return new Vin(raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT));
    }
}
