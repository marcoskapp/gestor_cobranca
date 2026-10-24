package br.com.gestorcobranca.assistant.domain.service;

import br.com.gestorcobranca.shared.domain.PixBrCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** Mensagem do Pix: só código dinâmico válido, valor do motor, validade e aviso de segurança. */
class PixMessageTest {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final Instant UNTIL = Instant.parse("2026-10-24T18:30:00Z");
    private static final String DYNAMIC = PixBrCode.dynamic("psp.credor.example/qr/v2/abc", "CREDOR EXEMPLO SA", "SAO PAULO");

    private static PixMessage.Content content(String code, String expected, String amount) {
        return new PixMessage.Content(PixMessage.Kind.INSTALLMENT, 2, new BigDecimal(expected), new BigDecimal(amount),
                UNTIL, code);
    }

    @Test
    void validDynamicCodeBuildsTheMessage() {
        PixMessage.Result r = PixMessage.build(content(DYNAMIC, "1234.56", "1234.56"), ZONE);
        assertThat(r.approved()).isTrue();
        assertThat(r.text()).contains("Pix da parcela 2 do seu acordo: R$ 1.234,56, válido até 24/10/2026 às 15:30")
                .contains(DYNAMIC).contains("o recebedor é CREDOR EXEMPLO SA")
                .contains("Não pedimos pagamento para chave Pix de pessoa física");
    }

    @Test
    void staticCodeWithKeyIsNeverSent() {
        assertThat(PixMessage.build(content(PixBrCodeFixtures.STATIC, "10.00", "10.00"), ZONE).errorCode())
                .isEqualTo("STATIC_PIX_CODE");
    }

    @Test
    void corruptedCodeIsRefused() {
        assertThat(PixMessage.build(content(DYNAMIC.replace("EXEMPLO", "GOLPEXX"), "10.00", "10.00"), ZONE).errorCode())
                .isEqualTo("INVALID_PIX_CODE");
        assertThat(PixMessage.build(content("chave 529.982.247-25", "10.00", "10.00"), ZONE).errorCode())
                .isEqualTo("INVALID_PIX_CODE");
    }

    @Test
    void amountMustBeExactlyTheInstallment() {
        assertThat(PixMessage.build(content(DYNAMIC, "400.00", "399.99"), ZONE).errorCode()).isEqualTo("AMOUNT_MISMATCH");
    }

    @Test
    void singlePaymentAndDownPaymentLabels() {
        assertThat(PixMessage.build(new PixMessage.Content(PixMessage.Kind.SINGLE, 1, BigDecimal.TEN, BigDecimal.TEN,
                UNTIL, DYNAMIC), ZONE).text()).startsWith("Pix do pagamento à vista: R$ 10,00");
        assertThat(PixMessage.build(new PixMessage.Content(PixMessage.Kind.DOWN_PAYMENT, 1, BigDecimal.TEN, BigDecimal.TEN,
                UNTIL, DYNAMIC), ZONE).text()).startsWith("Pix da entrada do seu acordo: R$ 10,00");
    }

    /** Código estático do manual do BCB (chave no campo 26.01). */
    static final class PixBrCodeFixtures {
        static final String STATIC = "00020126580014br.gov.bcb.pix0136123e4567-e12b-12d1-a456-4266554400005204000053039865802BR"
                + "5913Fulano de Tal6008BRASILIA62070503***63041D3D";
    }
}
