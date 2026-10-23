package br.com.gestorcobranca.payment.infrastructure.statement;

import br.com.gestorcobranca.payment.application.port.out.StatementParser.ParsedLine;
import br.com.gestorcobranca.payment.application.port.out.StatementParser.StatementFormatException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenericCsvStatementParserTest {

    private final GenericCsvStatementParser parser = new GenericCsvStatementParser();

    private List<ParsedLine> parse(String csv, int max) {
        return parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), max);
    }

    @Test
    void readsTheDocumentedLayout() {
        List<ParsedLine> lines = parse("""
                DATA;Valor;tipo;id_transacao;referencia;descricao
                2026-10-20;1234,56;C;E12345678901234567890123456789012;;PIX RECEBIDO
                21/10/2026;10.5;D;;REF-1;TARIFA

                2026-10-22;7;;;;
                """, 10);
        assertThat(lines).hasSize(3);
        assertThat(lines.getFirst().paidOn()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(lines.getFirst().amount()).isEqualByComparingTo(new BigDecimal("1234.56"));
        assertThat(lines.getFirst().credit()).isTrue();
        assertThat(lines.getFirst().transactionId()).hasSize(33);
        assertThat(lines.get(1).credit()).isFalse();
        assertThat(lines.get(1).reference()).isEqualTo("REF-1");
        assertThat(lines.get(2).description()).isNull();
        assertThat(lines.get(2).lineNumber()).isEqualTo(4);
    }

    @Test
    void rejectsInvalidFilesWithTheLineNumber() {
        assertThatThrownBy(() -> parse("valor\n10,00\n", 10)).isInstanceOf(StatementFormatException.class)
                .satisfies(e -> assertThat(((StatementFormatException) e).code()).isEqualTo("statement.header_invalid"));
        assertThatThrownBy(() -> parse("data;valor\n2026-13-01;10,00\n", 10))
                .satisfies(e -> assertThat(((StatementFormatException) e).code()).isEqualTo("statement.date_invalid"));
        assertThatThrownBy(() -> parse("data;valor\n2026-10-01;1.234,00\n", 10))
                .satisfies(e -> assertThat(((StatementFormatException) e).code()).isEqualTo("statement.amount_invalid"));
        assertThatThrownBy(() -> parse("data;valor\n2026-10-01;-5\n", 10))
                .satisfies(e -> assertThat(((StatementFormatException) e).lineNumber()).isEqualTo(2));
        assertThatThrownBy(() -> parse("data;valor;descricao\n2026-10-01;5;=HYPERLINK(1)\n", 10))
                .satisfies(e -> assertThat(((StatementFormatException) e).code()).isEqualTo("statement.formula_rejected"));
        assertThatThrownBy(() -> parse("data;valor;referencia\n2026-10-01;5;ref com espaço\n", 10))
                .satisfies(e -> assertThat(((StatementFormatException) e).code()).isEqualTo("statement.identifier_invalid"));
        assertThatThrownBy(() -> parse("data;valor\n2026-10-01;5\n2026-10-02;6\n", 1))
                .satisfies(e -> assertThat(((StatementFormatException) e).code()).isEqualTo("statement.too_many_lines"));
        byte[] latin1 = "data;valor;descricao\n2026-10-01;5;CRÉDITO\n".getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(latin1), 10))
                .satisfies(e -> assertThat(((StatementFormatException) e).code()).isEqualTo("statement.encoding_invalid"));
    }
}
