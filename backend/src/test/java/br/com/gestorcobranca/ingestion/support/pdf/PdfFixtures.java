package br.com.gestorcobranca.ingestion.support.pdf;

import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.ControlTotal;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.DebtorSection;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.DebtorSource;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.LabelField;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.NumberStyle;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.TableColumn;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.TableSection;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.TotalScope;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.ValuePosition;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.ValueTransform;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Os quatro layouts sintéticos da importação PDF (dados 100% fictícios, documentos com dígito válido e e-mails em
 * {@code exemplo.test}) e os mapas que a IA proporia para eles.
 */
public final class PdfFixtures {

    private static final float DATA = 7f;
    private static final float LABEL = 8f;

    private PdfFixtures() {
    }

    public static String brl(BigDecimal value) {
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.of("pt", "BR")));
        return "R$ " + f.format(value.setScale(2, RoundingMode.HALF_EVEN));
    }

    public static String plain(BigDecimal value) {
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.of("pt", "BR")));
        return f.format(value.setScale(2, RoundingMode.HALF_EVEN));
    }

    // ================================================================== layout 1: histórico (2 linhas por título)

    /** Título do histórico: valores que o teste confere. */
    public record HistoryTitle(String emp, String number, String installment, String issue, String due, String paid,
                               int delay, BigDecimal original, BigDecimal pending, BigDecimal interest,
                               String payment, String ourNumber, String account) {

        public String externalId() {
            return emp + "-" + number + "-" + installment;
        }
    }

    public static final String HISTORY_CNPJ = FakeDocuments.cnpj(1234);

    public static List<HistoryTitle> historyTitles(int count) {
        List<HistoryTitle> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            boolean odd = i % 2 == 1;
            BigDecimal original = new BigDecimal(odd ? "1320.98" : "455.63").add(BigDecimal.valueOf(i));
            BigDecimal pending = i == 2 ? new BigDecimal("200.00") : original;
            out.add(new HistoryTitle(odd ? "11" : "1", String.valueOf(32600 + i / 2), odd ? "A" + (i + 1) : "%02d".formatted(i + 1),
                    "06/04/2026", "%02d/%02d/2026".formatted(5 + i % 20, 6 + i % 4), "", 144 - i, original, pending,
                    new BigDecimal("75.35"), odd ? "237 - Boleto Fic" : "033 - Boleto Exe", "00000007332" + i,
                    odd ? "00043 - CLIENTES MONITORAMENTO" : "00047 - CLIENTES - VENDAS"));
        }
        return out;
    }

    private static final float[] H1_X = {30, 50, 85, 108, 150, 195, 222, 290, 330, 385, 430, 495};
    private static final String[] H1 = {"Emp", "Número", "Lt", "Emissão", "Vencto", "Pagto", "Atraso", "Valor",
            "Pendente", "Jur Mora", "Forma Pgto", "Nosso Número"};

    private static float right(int column) {
        return H1_X[column] + SyntheticPdf.width(H1[column], LABEL);
    }

    private static void historyPageTop(SyntheticPdf pdf, boolean withDebtor) {
        pdf.text(30, 20, LABEL, "Data Emissão: 27/10/2026");
        pdf.text(220, 18, 10, "Histórico Financeiro por Cliente");
        pdf.text(480, 20, LABEL, "Hora Emissão: 08:03");
        if (withDebtor) {
            pdf.text(30, 40, LABEL, "Cliente:").text(70, 40, LABEL, "024502 COMERCIO FICTICIO LTDA")
                    .text(300, 40, LABEL, "CPF/CNPJ:").text(345, 40, LABEL, FakeDocuments.maskedCnpj(HISTORY_CNPJ));
            pdf.text(30, 52, LABEL, "Endereço:").text(70, 52, LABEL, "RUA DAS FLORES FICTICIAS, 100")
                    .text(300, 52, LABEL, "Bairro:").text(345, 52, LABEL, "CENTRO");
            pdf.text(30, 64, LABEL, "Telefone:").text(180, 64, LABEL, "(11) 98765-4321")
                    .text(300, 64, LABEL, "Cidade:").text(345, 64, LABEL, "SAO PAULO");
            pdf.text(30, 76, LABEL, "Email:").text(70, 76, LABEL, "contato@exemplo.test");
        }
        float top = withDebtor ? 92 : 40;
        for (int c = 0; c < H1.length; c++) {
            pdf.text(H1_X[c], top, LABEL, H1[c]);
        }
        pdf.text(50, top + 9, LABEL, "Conta");
    }

    private static void historyLine1(SyntheticPdf pdf, float y, HistoryTitle t) {
        pdf.right(right(0), y, DATA, t.emp()).text(H1_X[1], y, DATA, t.number()).text(H1_X[2], y, DATA, t.installment())
                .text(H1_X[3], y, DATA, t.issue()).text(H1_X[4], y, DATA, t.due());
        if (!t.paid().isEmpty()) {
            pdf.text(H1_X[5], y, DATA, t.paid());
        }
        pdf.right(right(6), y, DATA, String.valueOf(t.delay())).right(right(7), y, DATA, brl(t.original()))
                .right(right(8), y, DATA, brl(t.pending())).right(right(9), y, DATA, brl(t.interest()))
                .text(H1_X[10], y, DATA, t.payment()).text(H1_X[11], y, DATA, t.ourNumber());
    }

    /**
     * Histórico financeiro por cliente: cabeçalho do devedor, títulos em duas linhas (a 2ª é a "Conta"), "Totais:" e
     * quadro de resumo.
     *
     * @param splitAt com valor, o título nessa posição (0..n-1) fica dividido entre a página 1 (1ª linha) e a 2 (2ª
     *                linha, depois do topo e do cabeçalho repetidos)
     */
    public static byte[] history(List<HistoryTitle> titles, Integer splitAt) {
        SyntheticPdf pdf = new SyntheticPdf().page();
        historyPageTop(pdf, true);
        float y = 115;
        for (int i = 0; i < titles.size(); i++) {
            HistoryTitle t = titles.get(i);
            historyLine1(pdf, y, t);
            if (splitAt != null && splitAt == i) {
                pdf.text(400, 820, LABEL, "Página 1 de 2");
                pdf.page();
                historyPageTop(pdf, false);
                y = 55;
            } else {
                y += 9;
            }
            pdf.text(50, y, DATA, t.account());
            y += 12;
        }
        BigDecimal original = titles.stream().map(HistoryTitle::original).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pending = titles.stream().map(HistoryTitle::pending).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal interest = titles.stream().map(HistoryTitle::interest).reduce(BigDecimal.ZERO, BigDecimal::add);
        pdf.text(190, y + 4, DATA, "Totais:").right(right(7), y + 4, DATA, brl(original))
                .right(right(8), y + 4, DATA, brl(pending)).right(right(9), y + 4, DATA, brl(interest));
        pdf.text(100, y + 30, LABEL, "Nº de Títulos:").right(220, y + 30, LABEL, "28")
                .text(260, y + 30, LABEL, "Títulos Vencidos:").right(420, y + 30, LABEL, brl(pending));
        pdf.text(400, 820, LABEL, splitAt == null ? "Página 1 de 1" : "Página 2 de 2");
        return pdf.build();
    }

    public static ExtractionMap historyMap() {
        List<String> line1 = List.of(H1);
        return new ExtractionMap(1, "Histórico financeiro por cliente", NumberStyle.COMMA,
                new DebtorSection(DebtorSource.HEADER, "Cliente:", List.of(
                        new LabelField("Cliente:", "nome", ValuePosition.RIGHT, ValueTransform.DROP_LEADING_CODE),
                        new LabelField("Cliente:", "info:codigo_cliente", ValuePosition.RIGHT, ValueTransform.LEADING_CODE),
                        new LabelField("CPF/CNPJ:", "documento", null, null),
                        new LabelField("Endereço:", "endereco_logradouro", null, null),
                        new LabelField("Bairro:", "endereco_bairro", null, null),
                        new LabelField("Telefone:", "telefone_1", null, null),
                        new LabelField("Cidade:", "endereco_cidade", null, null),
                        new LabelField("Email:", "email_1", null, null))),
                List.of(new LabelField("Data Emissão:", "data_saldo", null, null)),
                new TableSection(2, List.of(line1, List.of("Conta")), "Vencto", List.of("Totais:"), List.of(
                        col("Emp", 1, "id_titulo"), col("Número", 1, "id_titulo", "contrato"), col("Lt", 1, "id_titulo"),
                        col("Emissão", 1, "info:emissao"), col("Vencto", 1, "vencimento"),
                        col("Pagto", 1, ExtractionMap.PAID_WHEN_FILLED), col("Atraso", 1, "info:dias_atraso"),
                        col("Valor", 1, "valor_principal"), col("Pendente", 1, "saldo"),
                        col("Jur Mora", 1, "info:juros_mora"), col("Forma Pgto", 1, "info:forma_pagamento"),
                        col("Nosso Número", 1, "info:nosso_numero"), col("Conta", 2, "info:conta"))),
                List.of(new ControlTotal("Totais:", "Valor", "valor_principal"),
                        new ControlTotal("Totais:", "Pendente", "saldo")),
                List.of("Página"));
    }

    static TableColumn col(String header, int line, String... targets) {
        return new TableColumn(header, line, List.of(targets), null, null);
    }

    // ================================================================== layout 2: vários devedores, várias páginas

    public record ListDebtor(String name, String document, List<ListTitle> titles) {
    }

    public record ListTitle(String contract, int installment, String due, BigDecimal original, BigDecimal balance) {
    }

    public static List<ListDebtor> listDebtors() {
        List<ListDebtor> out = new ArrayList<>();
        out.add(new ListDebtor("ANA FICTICIA DE SOUZA", FakeDocuments.maskedCpf(FakeDocuments.cpf(11)), List.of(
                new ListTitle("CT-1001", 1, "10/01/2026", new BigDecimal("1500.00"), new BigDecimal("1612.40")),
                new ListTitle("CT-1001", 2, "10/02/2026", new BigDecimal("1500.00"), new BigDecimal("1580.10")))));
        List<ListTitle> many = new ArrayList<>();
        for (int i = 1; i <= 70; i++) {
            many.add(new ListTitle("CT-2002", i, "%02d/%02d/2025".formatted(1 + i % 27, 1 + i % 12),
                    new BigDecimal("250.75"), new BigDecimal("250.75").add(BigDecimal.valueOf(i))));
        }
        out.add(new ListDebtor("EMPRESA EXEMPLO COMERCIO LTDA", FakeDocuments.maskedCnpj(FakeDocuments.cnpj(22)), many));
        out.add(new ListDebtor("CARLOS TESTE PEREIRA", FakeDocuments.maskedCpf(FakeDocuments.cpf(33)), List.of(
                new ListTitle("CT-3003", 1, "05/03/2026", new BigDecimal("12345.67"), new BigDecimal("13000.00")))));
        return out;
    }

    private static final float[] L2_X = {30, 110, 160, 260, 340};
    private static final String[] L2 = {"Contrato", "Parcela", "Vencimento", "Valor Original", "Saldo"};

    private static float right2(int column) {
        return L2_X[column] + SyntheticPdf.width(L2[column], LABEL);
    }

    private static void listHeader(SyntheticPdf pdf, float y) {
        for (int c = 0; c < L2.length; c++) {
            pdf.text(L2_X[c], y, LABEL, L2[c]);
        }
    }

    /** Relação de títulos com vários devedores; a tabela do 2º devedor continua na página seguinte. */
    public static byte[] debtorList(List<ListDebtor> debtors) {
        SyntheticPdf pdf = new SyntheticPdf().page();
        pdf.text(30, 20, 10, "Relação de Títulos em Aberto").text(400, 22, LABEL, "Posição em: 30/10/2026");
        float y = 50;
        int page = 1;
        int count = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (ListDebtor d : debtors) {
            pdf.text(30, y, LABEL, "Devedor:").text(75, y, LABEL, d.name()).text(300, y, LABEL, "CPF/CNPJ:")
                    .text(345, y, LABEL, d.document());
            y += 14;
            listHeader(pdf, y);
            y += 12;
            BigDecimal original = BigDecimal.ZERO;
            BigDecimal balance = BigDecimal.ZERO;
            for (ListTitle t : d.titles()) {
                if (y > 780) {
                    pdf.text(400, 820, LABEL, "Página " + page + " de 2");
                    pdf.page();
                    page++;
                    pdf.text(30, 20, 10, "Relação de Títulos em Aberto (continuação)");
                    listHeader(pdf, 40);
                    y = 52;
                }
                pdf.text(L2_X[0], y, DATA, t.contract()).right(right2(1), y, DATA, String.valueOf(t.installment()))
                        .text(L2_X[2], y, DATA, t.due()).right(right2(3), y, DATA, plain(t.original()))
                        .right(right2(4), y, DATA, plain(t.balance()));
                original = original.add(t.original());
                balance = balance.add(t.balance());
                count++;
                y += 10;
            }
            pdf.text(30, y + 2, DATA, "Subtotal do devedor:").right(right2(3), y + 2, DATA, plain(original))
                    .right(right2(4), y + 2, DATA, plain(balance));
            total = total.add(balance);
            y += 24;
        }
        pdf.text(30, y, DATA, "Total geral:").right(right2(4), y, DATA, plain(total));
        pdf.text(30, y + 12, DATA, "Quantidade de títulos: " + count);
        pdf.text(400, 820, LABEL, "Página " + page + " de 2");
        return pdf.build();
    }

    public static ExtractionMap debtorListMap() {
        return new ExtractionMap(1, "Relação de títulos em aberto", NumberStyle.COMMA,
                new DebtorSection(DebtorSource.HEADER, "Devedor:", List.of(
                        new LabelField("Devedor:", "nome", null, null),
                        new LabelField("CPF/CNPJ:", "documento", null, null))),
                List.of(new LabelField("Posição em:", "data_saldo", null, null)),
                new TableSection(1, List.of(List.of(L2)), "Vencimento", List.of("Subtotal do devedor:", "Total geral:"),
                        List.of(col("Contrato", 1, "contrato", "id_titulo"), col("Parcela", 1, "parcela", "id_titulo"),
                                col("Vencimento", 1, "vencimento"), col("Valor Original", 1, "valor_principal"),
                                col("Saldo", 1, "saldo"))),
                List.of(new ControlTotal("Subtotal do devedor:", "Saldo", "saldo", TotalScope.BLOCK),
                        new ControlTotal("Subtotal do devedor:", "Valor Original", "valor_principal", TotalScope.BLOCK),
                        new ControlTotal("Total geral:", "Saldo", "saldo", TotalScope.DOCUMENT),
                        new ControlTotal("Quantidade de títulos:", null, ExtractionMap.COUNT_TARGET, TotalScope.DOCUMENT)),
                List.of("Página"));
    }

    // ================================================================== layout 3: tabela simples

    public record SimpleTitle(String document, String name, String contract, int installment, String due,
                              BigDecimal amount) {
    }

    public static List<SimpleTitle> simpleTitles() {
        return List.of(
                new SimpleTitle(FakeDocuments.maskedCpf(FakeDocuments.cpf(101)), "MARIA DA SILVA FICTICIA", "9001", 1, "15/07/2026", new BigDecimal("1000.00")),
                new SimpleTitle(FakeDocuments.maskedCpf(FakeDocuments.cpf(102)), "JOAO EXEMPLO", "9002", 3, "20/07/2026", new BigDecimal("2.50")),
                new SimpleTitle(FakeDocuments.cnpj(103), "LOJA TESTE ME", "9003", 1, "01/08/2026", new BigDecimal("1234567.89")));
    }

    private static final float[] L3_X = {30, 110, 260, 320, 370, 470};
    private static final String[] L3 = {"Documento", "Nome", "Contrato", "Parcela", "Vencimento", "Valor"};

    public static byte[] simpleTable(List<SimpleTitle> titles) {
        SyntheticPdf pdf = new SyntheticPdf().page();
        pdf.text(30, 20, 10, "Carteira para cobrança");
        for (int c = 0; c < L3.length; c++) {
            pdf.text(L3_X[c], 45, LABEL, L3[c]);
        }
        float valueRight = L3_X[5] + SyntheticPdf.width("Valor", LABEL) + 20;
        float y = 58;
        BigDecimal total = BigDecimal.ZERO;
        for (SimpleTitle t : titles) {
            pdf.text(L3_X[0], y, DATA, t.document()).text(L3_X[1], y, DATA, t.name()).text(L3_X[2], y, DATA, t.contract())
                    .text(L3_X[3], y, DATA, String.valueOf(t.installment())).text(L3_X[4], y, DATA, t.due())
                    .right(valueRight, y, DATA, plain(t.amount()));
            total = total.add(t.amount());
            y += 11;
        }
        pdf.text(30, y + 6, LABEL, "Total:").text(70, y + 6, LABEL, brl(total));
        pdf.text(30, y + 18, LABEL, "Quantidade:").text(85, y + 18, LABEL, String.valueOf(titles.size()));
        return pdf.build();
    }

    public static ExtractionMap simpleMap() {
        return new ExtractionMap(1, "Carteira para cobrança", NumberStyle.COMMA,
                new DebtorSection(DebtorSource.COLUMNS, null, List.of()), List.of(),
                new TableSection(1, List.of(List.of(L3)), "Vencimento", List.of("Total:"), List.of(
                        col("Documento", 1, "documento"), col("Nome", 1, "nome"),
                        col("Contrato", 1, "contrato", "id_titulo"), col("Parcela", 1, "parcela", "id_titulo"),
                        col("Vencimento", 1, "vencimento"), col("Valor", 1, "valor_principal"))),
                List.of(new ControlTotal("Total:", null, "valor_principal", TotalScope.DOCUMENT),
                        new ControlTotal("Quantidade:", null, ExtractionMap.COUNT_TARGET, TotalScope.DOCUMENT)),
                List.of());
    }

    // ================================================================== layout 4: escaneado

    public static byte[] scanned() {
        return SyntheticPdf.imageOnly(List.of("Carteira para cobranca (digitalizada)",
                "Documento   Nome   Vencimento   Valor",
                FakeDocuments.maskedCpf(FakeDocuments.cpf(201)) + "   PEDRO FICTICIO   10/09/2026   350,00"));
    }
}
