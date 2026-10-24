package br.com.gestorcobranca.shared.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * BR Code do Pix ("copia e cola"): payload EMV-MPM no padrão do Banco Central (Manual de Padrões para
 * Iniciação do Pix), composto por campos TLV ({@code ID(2) + tamanho(2) + valor}) e terminado pelo CRC16
 * (CCITT-FALSE, polinômio 0x1021, valor inicial 0xFFFF) no campo {@code 63}.
 * <p>
 * Interpretação usada pela plataforma: {@link #parse} lê e valida a estrutura e o CRC; {@link #dynamic}
 * monta um código dinâmico (usado pelo PSP simulado). Um código <strong>dinâmico</strong> traz no campo
 * {@code 26} a URL do payload no PSP ({@code 25}) e nenhuma chave ({@code 01}); o recebedor é o titular
 * da conta do PSP, nunca uma chave informada no texto.
 *
 * @param fields        campos de primeiro nível (ID → valor)
 * @param accountFields subcampos do campo 26 (informações da conta Pix)
 */
public record PixBrCode(Map<String, String> fields, Map<String, String> accountFields) {

    public static final String GUI = "br.gov.bcb.pix";
    private static final int MAX_LENGTH = 512;

    public PixBrCode {
        fields = Map.copyOf(fields);
        accountFields = Map.copyOf(accountFields);
    }

    /** Lê o código; vazio quando a estrutura TLV, o indicador de formato ou o CRC não conferem. */
    public static Optional<PixBrCode> parse(String code) {
        if (code == null || code.length() < 12 || code.length() > MAX_LENGTH
                || !code.chars().allMatch(c -> c >= 0x20 && c <= 0x7E)) {
            return Optional.empty();
        }
        int crcAt = code.length() - 8;
        if (!code.startsWith("6304", crcAt)) {
            return Optional.empty();
        }
        String expected = crc16(code.substring(0, crcAt + 4));
        if (!expected.equals(code.substring(crcAt + 4).toUpperCase(Locale.ROOT))) {
            return Optional.empty();
        }
        Optional<Map<String, String>> top = tlv(code.substring(0, crcAt));
        if (top.isEmpty() || !"01".equals(top.get().get("00"))) {
            return Optional.empty();
        }
        String account = top.get().get("26");
        Map<String, String> sub = account == null ? Map.of() : tlv(account).orElse(null);
        if (sub == null) {
            return Optional.empty();
        }
        return Optional.of(new PixBrCode(top.get(), sub));
    }

    /** Código dinâmico (URL do payload no PSP, sem chave) com o CRC calculado. */
    public static String dynamic(String location, String merchantName, String merchantCity) {
        String account = field("00", GUI) + field("25", location);
        String body = field("00", "01") + field("01", "12") + field("26", account) + field("52", "0000")
                + field("53", "986") + field("58", "BR") + field("59", merchantName) + field("60", merchantCity)
                + field("62", field("05", "***")) + "6304";
        return body + crc16(body);
    }

    /** Pix: identificador do arranjo {@code br.gov.bcb.pix} no campo 26. */
    public boolean isPix() {
        return GUI.equalsIgnoreCase(accountFields.getOrDefault("00", ""));
    }

    /** Dinâmico: URL do payload no PSP e nenhuma chave Pix no código. */
    public boolean isDynamic() {
        return accountFields.containsKey("25") && !accountFields.containsKey("01");
    }

    public Optional<String> staticKey() {
        return Optional.ofNullable(accountFields.get("01"));
    }

    public Optional<String> location() {
        return Optional.ofNullable(accountFields.get("25"));
    }

    /** Nome do recebedor (campo 59), exibido pelo app do banco do pagador. */
    public Optional<String> merchantName() {
        return Optional.ofNullable(fields.get("59")).map(String::trim).filter(s -> !s.isEmpty());
    }

    /** Moeda (campo 53; 986 = BRL). */
    public Optional<String> currency() {
        return Optional.ofNullable(fields.get("53"));
    }

    /** Valor do campo 54 (opcional no código dinâmico: o valor fica no payload do PSP). */
    public Optional<BigDecimal> amount() {
        String v = fields.get("54");
        if (v == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(v));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public Optional<String> country() {
        return Optional.ofNullable(fields.get("58"));
    }

    /** CRC16/CCITT-FALSE em hexadecimal maiúsculo (4 dígitos). */
    public static String crc16(String payload) {
        int crc = 0xFFFF;
        for (byte b : payload.getBytes(StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xFFFF;
            }
        }
        return "%04X".formatted(crc);
    }

    private static String field(String id, String value) {
        if (value.length() > 99) {
            throw new IllegalArgumentException("Campo " + id + " do BR Code acima de 99 caracteres");
        }
        return id + "%02d".formatted(value.length()) + value;
    }

    private static Optional<Map<String, String>> tlv(String data) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        while (i < data.length()) {
            if (i + 4 > data.length()) {
                return Optional.empty();
            }
            String id = data.substring(i, i + 2);
            String len = data.substring(i + 2, i + 4);
            if (!id.chars().allMatch(Character::isDigit) || !len.chars().allMatch(Character::isDigit)) {
                return Optional.empty();
            }
            int n = Integer.parseInt(len);
            if (i + 4 + n > data.length() || out.containsKey(id)) {
                return Optional.empty();
            }
            out.put(id, data.substring(i + 4, i + 4 + n));
            i += 4 + n;
        }
        return Optional.of(out);
    }
}
