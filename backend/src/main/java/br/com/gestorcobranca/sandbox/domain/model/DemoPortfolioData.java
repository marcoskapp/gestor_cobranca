package br.com.gestorcobranca.sandbox.domain.model;

import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.CreditorSpec;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.PortfolioSpec;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.TenantSpec;
import br.com.gestorcobranca.sandbox.domain.service.FictitiousData;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Devedores, contratos e títulos fictícios de uma assessoria, gerados de forma determinística a partir da semente do
 * catálogo e com datas relativas a "hoje" (atrasos e vencimentos fazem sentido em qualquer dia em que o sandbox subir).
 * <p>
 * A primeira carteira de cada credor começa com {@link #SCENARIO_DEBTORS} devedores de perfil controlado (dois títulos
 * de R$ 900 a R$ 2.400, atraso de 30 a 120 dias), usados nos cenários operacionais. Um grupo de devedores tem títulos
 * em mais de um credor ({@link #SHARED_DEBTORS}).
 */
public final class DemoPortfolioData {

    public static final int SCENARIO_DEBTORS = 12;
    public static final int SHARED_DEBTORS = 10;

    public record Debtor(String document, String name, LocalDate birthDate, String phone, boolean whatsapp,
                         String email) {

        public boolean company() {
            return document.length() == 14;
        }
    }

    public record Title(String externalId, int installment, LocalDate dueDate, BigDecimal principal) {
    }

    public record Vehicle(String plate, String description, int year, BigDecimal appraisal) {
    }

    public record Contract(String number, String product, LocalDate signedAt, BigDecimal amount, String instrumentType,
                           boolean executive, Vehicle vehicle, List<Title> titles) {
    }

    public record Debt(Debtor debtor, Contract contract) {
    }

    private final Map<String, List<Debt>> byPortfolio = new LinkedHashMap<>();

    private DemoPortfolioData() {
    }

    /** Dívidas por código de carteira, na ordem do catálogo. */
    public Map<String, List<Debt>> byPortfolio() {
        return byPortfolio;
    }

    public List<Debt> debts(String portfolioCode) {
        return byPortfolio.getOrDefault(portfolioCode, List.of());
    }

    public static DemoPortfolioData generate(TenantSpec tenant, LocalDate today) {
        DemoPortfolioData data = new DemoPortfolioData();
        FictitiousData random = new FictitiousData(tenant.seed());
        Set<String> documents = new HashSet<>();
        int[] sequence = {0};
        for (CreditorSpec creditor : tenant.creditors()) {
            for (int p = 0; p < creditor.portfolios().size(); p++) {
                PortfolioSpec spec = creditor.portfolios().get(p);
                List<Debt> debts = new ArrayList<>();
                for (int i = 0; i < spec.debtors(); i++) {
                    boolean scenario = p == 0 && i < SCENARIO_DEBTORS;
                    Debtor debtor = newDebtor(random, documents, sequence, today, companyPortfolio(spec));
                    debts.add(new Debt(debtor, contract(random, today, creditor, spec, i, scenario)));
                }
                data.byPortfolio.put(spec.code(creditor), debts);
            }
        }
        // devedores com títulos em mais de um credor: carteira 3 do 1º credor, carteira 2 do 2º e (parte) carteira 3 do 3º
        List<CreditorSpec> creditors = tenant.creditors();
        for (int s = 0; s < SHARED_DEBTORS; s++) {
            Debtor debtor = newDebtor(random, documents, sequence, today, false);
            addShared(data, random, today, creditors.get(0), 2, debtor, 900 + s);
            addShared(data, random, today, creditors.get(1), 1, debtor, 900 + s);
            if (s < 4) {
                addShared(data, random, today, creditors.get(2), 2, debtor, 900 + s);
            }
        }
        return data;
    }

    private static void addShared(DemoPortfolioData data, FictitiousData random, LocalDate today, CreditorSpec creditor,
                                  int portfolioIndex, Debtor debtor, int index) {
        PortfolioSpec spec = creditor.portfolios().get(portfolioIndex);
        List<Debt> debts = new ArrayList<>(data.debts(spec.code(creditor)));
        debts.add(new Debt(debtor, contract(random, today, creditor, spec, index, false)));
        data.byPortfolio.put(spec.code(creditor), debts);
    }

    private static boolean companyPortfolio(PortfolioSpec spec) {
        return spec.suffix().equals("EMPRESAS") || spec.suffix().equals("COMERCIAL");
    }

    private static Debtor newDebtor(FictitiousData random, Set<String> documents, int[] sequence, LocalDate today,
                                    boolean company) {
        String document;
        do {
            document = company ? random.cnpj() : random.cpf();
        } while (!documents.add(document));
        sequence[0]++;
        String name = company ? random.companyName() : random.personName();
        LocalDate birth = company ? null : random.birthDate(today);
        String phone = random.mobilePhone();
        boolean whatsapp = random.chance(85);
        String email = random.chance(80) ? random.email(name, sequence[0]) : null;
        return new Debtor(document, name, birth, phone, whatsapp, email);
    }

    private static Contract contract(FictitiousData random, LocalDate today, CreditorSpec creditor, PortfolioSpec spec,
                                     int index, boolean scenario) {
        String number = "%s-%05d".formatted(spec.code(creditor), index + 1);
        int titles = scenario ? 2 : random.between(1, spec.maxTitles());
        int overdue = scenario ? random.between(30, 120) : random.between(spec.minDaysOverdue(), spec.maxDaysOverdue());
        LocalDate firstDue = today.minusDays(overdue);
        List<Title> list = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int t = 0; t < titles; t++) {
            int whole = scenario ? random.between(900, 2400) : random.between(spec.minPrincipal(), spec.maxPrincipal());
            BigDecimal principal = BigDecimal.valueOf(whole * 100L + random.between(0, 99), 2);
            total = total.add(principal);
            list.add(new Title(number + "-P" + (t + 1), t + 1, firstDue.plusMonths(t), principal));
        }
        LocalDate signedAt = firstDue.minusMonths(1).minusDays(random.between(0, 300));
        BigDecimal amount = total.multiply(BigDecimal.valueOf(random.between(100, 140)))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN);
        Vehicle vehicle = spec.vehicleCollateral()
                ? new Vehicle(random.plate(), "Automóvel hatch compacto (fictício)", random.between(2016, 2022),
                BigDecimal.valueOf(random.between(25, 60) * 1000L))
                : null;
        return new Contract(number, spec.product(), signedAt, amount, spec.instrumentType(), spec.executive(), vehicle,
                List.copyOf(list));
    }
}
