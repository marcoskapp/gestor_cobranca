package br.com.gestorcobranca.shared.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** BR Code do Pix: estrutura TLV, CRC16/CCITT-FALSE e código dinâmico × estático. */
class PixBrCodeTest {

    /** Exemplo de código estático (com chave) do Manual de Padrões para Iniciação do Pix do BCB. */
    static final String STATIC = "00020126580014br.gov.bcb.pix0136123e4567-e12b-12d1-a456-4266554400005204000053039865802BR"
            + "5913Fulano de Tal6008BRASILIA62070503***63041D3D";

    @Test
    void crcMatchesTheStandardCheckValue() {
        assertThat(PixBrCode.crc16("123456789")).isEqualTo("29B1");
    }

    @Test
    void staticCodeFromTheCentralBankManualIsReadAsStatic() {
        PixBrCode code = PixBrCode.parse(STATIC).orElseThrow();
        assertThat(code.isPix()).isTrue();
        assertThat(code.isDynamic()).isFalse();
        assertThat(code.staticKey()).contains("123e4567-e12b-12d1-a456-426655440000");
        assertThat(code.merchantName()).contains("Fulano de Tal");
    }

    @Test
    void dynamicCodeRoundTripsWithoutKey() {
        String text = PixBrCode.dynamic("psp.example/qr/v2/abc123", "CREDOR SA", "SAO PAULO");
        PixBrCode code = PixBrCode.parse(text).orElseThrow();
        assertThat(code.isDynamic()).isTrue();
        assertThat(code.staticKey()).isEmpty();
        assertThat(code.location()).contains("psp.example/qr/v2/abc123");
        assertThat(code.currency()).contains("986");
        assertThat(code.country()).contains("BR");
        assertThat(code.amount()).isEmpty();
    }

    @Test
    void corruptedOrMalformedCodesAreRejected() {
        String text = PixBrCode.dynamic("psp.example/qr/v2/abc123", "CREDOR SA", "SAO PAULO");
        assertThat(PixBrCode.parse(text.replace("CREDOR", "GOLPES"))).isEmpty();
        assertThat(PixBrCode.parse(text.substring(0, text.length() - 1) + "0")).isEmpty();
        assertThat(PixBrCode.parse("pague para a chave 11999999999")).isEmpty();
        assertThat(PixBrCode.parse(null)).isEmpty();
        assertThat(PixBrCode.parse(STATIC.replace("0136", "0199"))).isEmpty();
    }

    @Test
    void amountFieldIsRead() {
        String account = f("00", PixBrCode.GUI) + f("25", "psp.example/q/1");
        String body = f("00", "01") + f("01", "12") + f("26", account) + f("52", "0000") + f("53", "986")
                + f("54", "150.00") + f("58", "BR") + f("59", "CREDOR SA") + f("60", "SAO PAULO") + "6304";
        assertThat(PixBrCode.parse(body + PixBrCode.crc16(body)).orElseThrow().amount())
                .contains(new BigDecimal("150.00"));
    }

    static String f(String id, String value) {
        return id + "%02d".formatted(value.length()) + value;
    }
}
