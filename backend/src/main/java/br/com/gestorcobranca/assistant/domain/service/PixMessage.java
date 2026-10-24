package br.com.gestorcobranca.assistant.domain.service;

import br.com.gestorcobranca.assistant.domain.model.AllowedFacts;
import br.com.gestorcobranca.shared.domain.PixBrCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Mensagem do Pix ao devedor, montada <strong>só pelo servidor</strong> a partir de um modelo fixo — o
 * modelo de linguagem nunca escreve o código. Antes do envio:
 * <ul>
 *   <li>o "copia e cola" precisa ser um BR Code Pix válido (estrutura EMV e CRC16), <strong>dinâmico</strong>
 *       (URL do payload no PSP, sem chave Pix no código — nunca chave de pessoa física), em reais (986),
 *       com o nome do recebedor (campo 59) e, se trouxer valor (campo 54), igual ao da parcela;</li>
 *   <li>o valor da instrução precisa ser exatamente o da parcela calculada pelo motor;</li>
 *   <li>o texto fora do código passa pelo {@link ResponseGuard} com os únicos fatos permitidos (valor da
 *       parcela, data da validade, número da parcela): o valor do motor passa e nada mais passa.</li>
 * </ul>
 * O nome do recebedor vem do próprio código (é o que o app do banco exibe) e entra no aviso de segurança.
 */
public final class PixMessage {

    private static final Pattern MERCHANT = Pattern.compile("[A-Za-z0-9 .,&'/-]{2,25}");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** Tipo da parcela no texto. */
    public enum Kind {
        SINGLE("do pagamento à vista"), DOWN_PAYMENT("da entrada do seu acordo"), INSTALLMENT("da parcela %d do seu acordo");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        String label(int number) {
            return label.formatted(number);
        }
    }

    /**
     * @param expectedAmount saldo da parcela pelo motor (o valor do Pix tem de ser este)
     * @param amount         valor da instrução de pagamento
     */
    public record Content(Kind kind, int installmentNumber, BigDecimal expectedAmount, BigDecimal amount,
                          Instant expiresAt, String copyPaste) {
    }

    /** @param errorCode motivo da recusa (nulo quando aprovada) */
    public record Result(String text, String errorCode, List<String> violations) {

        public Result {
            violations = violations == null ? List.of() : List.copyOf(violations);
        }

        public boolean approved() {
            return errorCode == null;
        }
    }

    private PixMessage() {
    }

    public static Result build(Content c, ZoneId zone) {
        Optional<PixBrCode> parsed = PixBrCode.parse(c.copyPaste() == null ? null : c.copyPaste().trim());
        if (parsed.isEmpty() || !parsed.get().isPix()) {
            return refused("INVALID_PIX_CODE");
        }
        PixBrCode code = parsed.get();
        if (!code.isDynamic()) {
            return refused("STATIC_PIX_CODE");
        }
        if (!"986".equals(code.currency().orElse("")) || !"BR".equals(code.country().orElse(""))) {
            return refused("INVALID_PIX_CODE");
        }
        String merchant = code.merchantName().orElse("");
        if (!MERCHANT.matcher(merchant).matches()) {
            return refused("RECEIVER_NAME_INVALID");
        }
        BigDecimal expected = money(c.expectedAmount());
        if (c.amount() == null || expected == null || money(c.amount()).compareTo(expected) != 0
                || code.amount().map(a -> money(a).compareTo(expected) != 0).orElse(false)) {
            return refused("AMOUNT_MISMATCH");
        }
        ZonedDateTime until = c.expiresAt().atZone(zone);
        String head = "Pix " + c.kind().label(c.installmentNumber()) + ": " + format(expected) + ", válido até "
                + until.format(DATE) + " às " + until.format(TIME) + ".";
        String howTo = "Copie o código abaixo e cole na opção \"Pix Copia e Cola\" do app do seu banco:";
        String safety = "Segurança: antes de confirmar, confira no app do banco se o recebedor é " + merchant
                + ". Não pedimos pagamento para chave Pix de pessoa física nem por outros canais.";
        String guarded = head + "\n\n" + howTo + "\n\n" + safety;
        AllowedFacts facts = AllowedFacts.EMPTY.withAmounts(List.of(expected)).withDates(List.of(until.toLocalDate()))
                .withInstallments(List.of(c.installmentNumber()));
        ResponseGuard.Result guard = ResponseGuard.check(guarded, facts, new ResponseGuard.Config(List.of(), false));
        if (!guard.approved()) {
            return new Result(null, "GUARD_REJECTED", guard.violations());
        }
        return new Result(head + "\n\n" + howTo + "\n\n" + c.copyPaste().trim() + "\n\n" + safety, null, List.of());
    }

    private static Result refused(String code) {
        return new Result(null, code, List.of());
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_EVEN);
    }

    private static String format(BigDecimal v) {
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.of("pt", "BR")));
        return "R$ " + f.format(v);
    }
}
