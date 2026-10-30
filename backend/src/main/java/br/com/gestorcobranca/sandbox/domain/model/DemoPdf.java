package br.com.gestorcobranca.sandbox.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * PDF <strong>fictício</strong> de demonstração da Importação PDF inteligente: o conteúdo (dados inventados pelo
 * gerador do sandbox) e o layout em que ele é desenhado. O desenho em PDF fica no adaptador de infraestrutura.
 *
 * @param fileName nome do arquivo entregue ao responsável pela demonstração (e enviado na importação)
 * @param purpose  para que serve na demonstração (aparece no índice lido pelo {@code scripts/sandbox/pdfs.sh})
 */
public record DemoPdf(String fileName, String purpose, Document document) {

    public DemoPdf {
        Objects.requireNonNull(fileName);
        Objects.requireNonNull(document);
    }

    /** Conteúdo de um layout. */
    public sealed interface Document permits History, Table {
    }

    /**
     * "Histórico Financeiro por Cliente" (layout de ERP): cabeçalho do devedor, títulos em duas linhas (a 2ª é a
     * "Conta"), linha "Totais:" e quadro de resumo. Os totais impressos normalmente são a soma dos títulos; diferentes,
     * demonstram o bloqueio por divergência de totais.
     *
     * @param issuedOn        "Data Emissão" (vira a data-base do saldo)
     * @param printedOriginal total impresso da coluna "Valor"
     * @param printedPending  total impresso da coluna "Pendente"
     * @param printedInterest total impresso da coluna "Jur Mora"
     */
    public record History(LocalDate issuedOn, String issuedAt, HistoryDebtor debtor, List<HistoryTitle> titles,
                          BigDecimal printedOriginal, BigDecimal printedPending, BigDecimal printedInterest)
            implements Document {

        public History {
            titles = List.copyOf(titles);
        }

        public BigDecimal sumOriginal() {
            return titles.stream().map(HistoryTitle::original).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal sumPending() {
            return titles.stream().map(HistoryTitle::pending).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal sumInterest() {
            return titles.stream().map(HistoryTitle::interest).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /** Os totais impressos conferem com a soma dos títulos. */
        public boolean totalsConsistent() {
            return printedOriginal.compareTo(sumOriginal()) == 0 && printedPending.compareTo(sumPending()) == 0
                    && printedInterest.compareTo(sumInterest()) == 0;
        }
    }

    /**
     * @param code     código do cliente no ERP do credor
     * @param document CPF/CNPJ com máscara
     * @param phone    telefone formatado ({@code (11) 99999-9999})
     */
    public record HistoryDebtor(String code, String name, String document, String street, String district,
                                String phone, String city, String email) {
    }

    /**
     * @param company     "Emp" (empresa do grupo no ERP)
     * @param installment "Lt" (parcela/letra)
     * @param delay       dias de atraso na data de emissão
     */
    public record HistoryTitle(String company, String number, String installment, LocalDate issue, LocalDate due,
                               int delay, BigDecimal original, BigDecimal pending, BigDecimal interest,
                               String payment, String ourNumber, String account) {
    }

    /**
     * Tabela simples, um título por linha e o devedor nas colunas, com "Total:" e "Quantidade:" no fim. Sem mapa salvo
     * no sandbox: com a IA desligada, o mapa é montado à mão na tela.
     */
    public record Table(String heading, LocalDate position, List<TableRow> rows) implements Document {

        public Table {
            rows = List.copyOf(rows);
        }

        public BigDecimal total() {
            return rows.stream().map(TableRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /** @param document CPF/CNPJ com máscara */
    public record TableRow(String document, String name, String contract, int installment, LocalDate due,
                           BigDecimal amount) {
    }
}
