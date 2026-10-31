package br.com.gestorcobranca.sandbox.domain.service;

import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.CreditorSpec;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.TenantSpec;
import br.com.gestorcobranca.sandbox.domain.model.DemoPdf;
import br.com.gestorcobranca.sandbox.domain.model.DemoPdf.History;
import br.com.gestorcobranca.sandbox.domain.model.DemoPdf.HistoryDebtor;
import br.com.gestorcobranca.sandbox.domain.model.DemoPdf.HistoryTitle;
import br.com.gestorcobranca.sandbox.domain.model.DemoPdf.Table;
import br.com.gestorcobranca.sandbox.domain.model.DemoPdf.TableRow;
import br.com.gestorcobranca.sandbox.domain.model.DemoPdfLayouts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Conteúdo <strong>fictício</strong> dos PDFs de demonstração da Importação PDF de uma assessoria do sandbox, todos do
 * 1º credor do catálogo: devedores inventados pelo {@link FictitiousData} (CPF/CNPJ com dígito válido gerado ao acaso,
 * e-mails em {@code exemplo.test}), datas relativas ao dia da carga e números de título distintos em cada arquivo (um
 * não colide com o outro na carteira). Determinístico pela semente da assessoria.
 */
public final class DemoPdfFactory {

    /** Diferença impressa a mais no total "Pendente" do PDF com divergência de totais. */
    public static final BigDecimal DIVERGENCE = new BigDecimal("150.00");
    /** Títulos do PDF conferido pelo {@code verify.sh} (a verificação compara a contagem). */
    public static final int API_CHECK_TITLES = 3;
    /** Títulos do PDF longo (mais que uma página da lista de títulos da tela, que mostra 50). */
    public static final int LONG_TITLES = 64;

    private static final List<String> STREETS = List.of("DAS ACÁCIAS", "DOS IPÊS", "DO CAMPO ALEGRE", "DA PRAINHA",
            "DAS PALMEIRAS", "DO MIRANTE", "DOS GIRASSÓIS", "DA ALVORADA");
    private static final List<String> DISTRICTS = List.of("CENTRO", "JARDIM AMÉRICA", "VILA NOVA", "BOA VISTA",
            "SANTA CRUZ", "PARQUE INDUSTRIAL");
    private static final List<String> CITIES = List.of("SAO PAULO", "CAMPINAS", "BELO HORIZONTE", "CURITIBA",
            "PORTO ALEGRE", "RECIFE");
    private static final List<String> PAYMENTS = List.of("033 - Boleto", "237 - Boleto", "001 - Débito");
    private static final List<String> ACCOUNTS = List.of("00047 - CLIENTES - VENDAS", "00043 - CLIENTES MONITORAMENTO");

    /**
     * PDFs de uma assessoria.
     *
     * @param model     PDF do layout "histórico" usado só pela carga para salvar o mapa do credor (depois descartado;
     *                  não é entregue)
     * @param published PDFs entregues ao responsável pela demonstração, na ordem do roteiro
     * @param apiCheck  PDF enviado pelo {@code verify.sh} pela API pública (também entregue, para referência)
     */
    public record DemoPdfSet(String creditorPrefix, String creditorName, String portfolioCode, DemoPdf model,
                             List<DemoPdf> published, DemoPdf apiCheck) {

        public List<DemoPdf> files() {
            List<DemoPdf> all = new ArrayList<>(published);
            all.add(apiCheck);
            return List.copyOf(all);
        }
    }

    private DemoPdfFactory() {
    }

    public static DemoPdfSet forTenant(TenantSpec spec, LocalDate today) {
        CreditorSpec creditor = spec.creditors().getFirst();
        String p = creditor.prefix().toLowerCase(Locale.ROOT);
        String portfolio = creditor.portfolios().getFirst().code(creditor);
        DemoPdf model = new DemoPdf("modelo-historico-financeiro-" + p + ".pdf",
                "Modelo do layout usado pela carga para salvar o mapa do credor", history(spec, today, 1, 41000, 4, false));
        List<DemoPdf> published = List.of(
                new DemoPdf("historico-financeiro-" + p + ".pdf",
                        "Layout com mapa salvo: mapa reaproveitado sem IA, validação e importação",
                        history(spec, today, 2, 42000, 10, false)),
                new DemoPdf("historico-financeiro-" + p + "-totais-divergentes.pdf",
                        "Mesmo layout com o total Pendente impresso R$ 150,00 acima da soma: bloqueio por divergência",
                        history(spec, today, 3, 43000, 6, true)),
                new DemoPdf("tabela-simples-" + p + ".pdf",
                        "Outro layout, sem mapa salvo: com a IA desligada, montar o mapa à mão na tela",
                        table(spec, today, 4)),
                new DemoPdf("historico-financeiro-" + p + "-longo.pdf",
                        "Mesmo layout com " + LONG_TITLES + " títulos em várias páginas: filtros, seleção de todos do filtro"
                                + " e exclusão em massa",
                        history(spec, today, 6, 45000, LONG_TITLES, false)));
        DemoPdf apiCheck = new DemoPdf("verificacao-api-" + p + ".pdf",
                "Enviado pelo verify.sh pela API pública (não use na demonstração pela tela)",
                history(spec, today, 5, 44000, API_CHECK_TITLES, false));
        return new DemoPdfSet(creditor.prefix(), creditor.tradeName(), portfolio, model, published, apiCheck);
    }

    private static History history(TenantSpec spec, LocalDate today, int stream, int numberBase, int count,
                                   boolean divergent) {
        FictitiousData data = new FictitiousData(spec.seed() * 31 + stream);
        String name = plainUpper(data.companyName());
        HistoryDebtor debtor = new HistoryDebtor("%06d".formatted(data.between(1000, 99999)), name,
                maskCnpj(data.cnpj()), "RUA " + data.pick(STREETS) + ", " + data.between(10, 2400),
                data.pick(DISTRICTS), phone(data.mobilePhone()), data.pick(CITIES), data.email(name, stream));
        List<HistoryTitle> titles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            boolean branch = i % 3 == 2;
            int delay = data.between(20, 240);
            LocalDate due = today.minusDays(delay);
            BigDecimal original = cents(data.between(15_000, 240_000));
            BigDecimal pending = data.chance(25)
                    ? original.multiply(BigDecimal.valueOf(data.between(30, 80))).movePointLeft(2)
                    .setScale(2, RoundingMode.HALF_EVEN)
                    : original;
            BigDecimal interest = pending.multiply(BigDecimal.valueOf(delay)).multiply(new BigDecimal("0.00033"))
                    .setScale(2, RoundingMode.HALF_EVEN);
            titles.add(new HistoryTitle(branch ? "11" : "1", String.valueOf(numberBase + i / 2),
                    branch ? "A" + (i + 1) : "%02d".formatted(i + 1), due.minusDays(data.between(25, 40)), due, delay,
                    original, pending, interest, data.pick(PAYMENTS), "%011d".formatted(numberBase * 1000L + i),
                    data.pick(ACCOUNTS)));
        }
        History exact = new History(today, "08:00", debtor, titles, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        return new History(today, "08:00", debtor, titles, exact.sumOriginal(),
                divergent ? exact.sumPending().add(DIVERGENCE) : exact.sumPending(), exact.sumInterest());
    }

    private static Table table(TenantSpec spec, LocalDate today, int stream) {
        FictitiousData data = new FictitiousData(spec.seed() * 31 + stream);
        List<TableRow> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            boolean company = i % 3 == 1;
            String document = company ? maskCnpj(data.cnpj()) : maskCpf(data.cpf());
            String name = plainUpper(company ? data.companyName() : data.personName());
            int installments = data.between(1, 3);
            for (int n = 1; n <= installments && rows.size() < 8; n++) {
                rows.add(new TableRow(document, name, "CT-" + (9100 + i), n, today.minusDays(data.between(15, 200)),
                        cents(data.between(8_000, 350_000))));
            }
        }
        return new Table(DemoPdfLayouts.TABLE_HEADING, today, rows);
    }

    static BigDecimal cents(int value) {
        return BigDecimal.valueOf(value, 2);
    }

    /** Maiúsculas sem acento, como nos relatórios de ERP. */
    static String plainUpper(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
    }

    static String maskCnpj(String d) {
        return d.substring(0, 2) + "." + d.substring(2, 5) + "." + d.substring(5, 8) + "/" + d.substring(8, 12) + "-"
                + d.substring(12);
    }

    static String maskCpf(String d) {
        return d.substring(0, 3) + "." + d.substring(3, 6) + "." + d.substring(6, 9) + "-" + d.substring(9);
    }

    /** {@code +55DDNNNNNNNNN} → {@code (DD) NNNNN-NNNN}. */
    static String phone(String e164) {
        String n = e164.substring(5);
        return "(" + e164.substring(3, 5) + ") " + n.substring(0, 5) + "-" + n.substring(5);
    }
}
