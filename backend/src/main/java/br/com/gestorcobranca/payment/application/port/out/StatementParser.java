package br.com.gestorcobranca.payment.application.port.out;

import br.com.gestorcobranca.payment.domain.model.StatementFormat;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Leitor de extrato / arquivo de retorno de um formato. Implementado para {@code CSV_GENERIC}
 * ({@code docs/negocio/layout-extrato-conciliacao.md}); CNAB 240/400 são formatos futuros (sem leitor).
 */
public interface StatementParser {

    StatementFormat format();

    /**
     * @param maxLines limite de linhas de dados (acima → {@link StatementFormatException})
     * @throws StatementFormatException arquivo ilegível ou linha inválida (com o número da linha)
     */
    List<ParsedLine> parse(InputStream content, int maxLines);

    /**
     * @param credit        crédito (C) ou débito (D)
     * @param transactionId identificador da transação no banco/PSP ({@code endToEndId}, nosso número)
     * @param reference     referência informada pelo pagador ou banco ({@code txid}, id de instrução,
     *                      pagamento ou parcela)
     */
    record ParsedLine(int lineNumber, LocalDate paidOn, BigDecimal amount, boolean credit, String transactionId,
                      String reference, String description) {
    }

    class StatementFormatException extends RuntimeException {

        private final String code;
        private final int lineNumber;

        public StatementFormatException(String code, int lineNumber, String message) {
            super(message);
            this.code = code;
            this.lineNumber = lineNumber;
        }

        public String code() {
            return code;
        }

        public int lineNumber() {
            return lineNumber;
        }
    }
}
