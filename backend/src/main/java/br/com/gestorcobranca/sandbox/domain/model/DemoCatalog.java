package br.com.gestorcobranca.sandbox.domain.model;

import java.util.List;

/**
 * Catálogo fixo do sandbox: duas assessorias fictícias, três credores por assessoria, quatro carteiras por credor e
 * uma pessoa de demonstração para cada perfil padrão (e um segundo operador com escopo restrito a um credor, para
 * demonstrar o escopo por credor). Tudo é inventado; e-mails usam {@code <assessoria>.exemplo.test}.
 */
public final class DemoCatalog {

    /** Código do perfil padrão do segundo operador (mesmo perfil OPERADOR, outro usuário). */
    public static final String SECOND_OPERATOR_KEY = "operador2";

    public enum Segment { BANK, RETAIL, SERVICES }

    /** Escopo de credores do usuário: todos ou só o primeiro credor do catálogo da assessoria. */
    public enum Scope { ALL_CREDITORS, FIRST_CREDITOR }

    /**
     * @param key      identificador da pessoa no catálogo (parte local do e-mail)
     * @param roleCode perfil padrão atribuído
     */
    public record Person(String key, String roleCode, String name, Scope scope) {

        public String email(TenantSpec tenant) {
            return key + "@" + tenant.emailDomain();
        }
    }

    /**
     * @param suffix          parte do código da carteira ({@code <prefixo do credor>-<suffix>})
     * @param debtors         devedores próprios da carteira
     * @param instrumentType  natureza do título no layout de importação (nulo = não informada)
     * @param vehicleCollateral contratos com alienação fiduciária de veículo (base do fluxo jurídico)
     */
    public record PortfolioSpec(String suffix, String name, String product, int debtors, int minDaysOverdue,
                                int maxDaysOverdue, int minPrincipal, int maxPrincipal, int maxTitles,
                                String instrumentType, boolean executive, boolean vehicleCollateral) {

        public String code(CreditorSpec creditor) {
            return creditor.prefix() + "-" + suffix;
        }
    }

    public record CreditorSpec(String prefix, String legalName, String tradeName, Segment segment,
                               List<PortfolioSpec> portfolios) {
    }

    /**
     * @param seed semente do gerador de dados fictícios (devedores reprodutíveis)
     */
    public record TenantSpec(String slug, String legalName, String tradeName, String emailDomain, long seed,
                             List<CreditorSpec> creditors, List<Person> people) {

        public String contactEmail(String local) {
            return local + "@" + emailDomain;
        }
    }

    private DemoCatalog() {
    }

    public static List<TenantSpec> tenants() {
        return List.of(aurora(), boreal());
    }

    private static TenantSpec aurora() {
        return new TenantSpec("aurora", "Aurora Assessoria de Cobrança Ltda (fictícia)", "Aurora Cobrança",
                "aurora.exemplo.test", 20261101L,
                List.of(
                        new CreditorSpec("HZN", "Banco Horizonte S.A. (fictício)", "Banco Horizonte", Segment.BANK,
                                bankPortfolios()),
                        new CreditorSpec("EST", "Rede Varejo Estrela Ltda (fictícia)", "Varejo Estrela", Segment.RETAIL,
                                retailPortfolios()),
                        new CreditorSpec("TVC", "Telecom Vale Conecta S.A. (fictícia)", "Vale Conecta", Segment.SERVICES,
                                telecomPortfolios())),
                List.of(
                        new Person("admin", "ADMIN", "Helena Prado", Scope.ALL_CREDITORS),
                        new Person("gestor", "GESTOR", "Rafael Monteiro", Scope.ALL_CREDITORS),
                        new Person("supervisor", "SUPERVISOR", "Camila Torres", Scope.ALL_CREDITORS),
                        new Person("operador", "OPERADOR", "Lucas Ferraz", Scope.ALL_CREDITORS),
                        new Person(SECOND_OPERATOR_KEY, "OPERADOR", "Bianca Nogueira", Scope.FIRST_CREDITOR),
                        new Person("financeiro", "FINANCEIRO", "Marcos Viana", Scope.ALL_CREDITORS),
                        new Person("auditor", "AUDITOR", "Patrícia Leal", Scope.ALL_CREDITORS),
                        new Person("juridico", "JURIDICO", "Eduardo Sales", Scope.ALL_CREDITORS),
                        new Person("advogado", "ADVOGADO_PARCEIRO", "Fernanda Rocha", Scope.FIRST_CREDITOR),
                        new Person("analista", "ANALISTA_CARTEIRA", "Gustavo Pires", Scope.ALL_CREDITORS),
                        new Person("estrategia", "ESTRATEGIA", "Larissa Campos", Scope.ALL_CREDITORS),
                        new Person("qualidade", "QUALIDADE", "Renata Duarte", Scope.ALL_CREDITORS),
                        new Person("compliance", "COMPLIANCE", "Otávio Brandão", Scope.ALL_CREDITORS),
                        new Person("integracao", "INTEGRACAO", "Igor Matos", Scope.ALL_CREDITORS)));
    }

    private static TenantSpec boreal() {
        return new TenantSpec("boreal", "Boreal Recuperação de Crédito S.A. (fictícia)", "Boreal Crédito",
                "boreal.exemplo.test", 20261102L,
                List.of(
                        new CreditorSpec("CSR", "Cooperativa de Crédito Sertão (fictícia)", "Coop Sertão", Segment.BANK,
                                bankPortfolios()),
                        new CreditorSpec("ELT", "Energia Litoral Distribuidora S.A. (fictícia)", "Energia Litoral",
                                Segment.SERVICES, utilityPortfolios()),
                        new CreditorSpec("UMC", "Universidade Monte Claro (fictícia)", "Monte Claro", Segment.RETAIL,
                                educationPortfolios())),
                List.of(
                        new Person("admin", "ADMIN", "Sérgio Albuquerque", Scope.ALL_CREDITORS),
                        new Person("gestor", "GESTOR", "Mônica Teixeira", Scope.ALL_CREDITORS),
                        new Person("supervisor", "SUPERVISOR", "André Paiva", Scope.ALL_CREDITORS),
                        new Person("operador", "OPERADOR", "Juliana Costa", Scope.ALL_CREDITORS),
                        new Person(SECOND_OPERATOR_KEY, "OPERADOR", "Caio Martins", Scope.FIRST_CREDITOR),
                        new Person("financeiro", "FINANCEIRO", "Tereza Lima", Scope.ALL_CREDITORS),
                        new Person("auditor", "AUDITOR", "Roberto Assis", Scope.ALL_CREDITORS),
                        new Person("juridico", "JURIDICO", "Vanessa Moura", Scope.ALL_CREDITORS),
                        new Person("advogado", "ADVOGADO_PARCEIRO", "Henrique Dias", Scope.FIRST_CREDITOR),
                        new Person("analista", "ANALISTA_CARTEIRA", "Priscila Neves", Scope.ALL_CREDITORS),
                        new Person("estrategia", "ESTRATEGIA", "Daniel Freitas", Scope.ALL_CREDITORS),
                        new Person("qualidade", "QUALIDADE", "Simone Arruda", Scope.ALL_CREDITORS),
                        new Person("compliance", "COMPLIANCE", "Fábio Rezende", Scope.ALL_CREDITORS),
                        new Person("integracao", "INTEGRACAO", "Natália Prates", Scope.ALL_CREDITORS)));
    }

    // A primeira carteira de cada credor concentra os cenários operacionais (acordos, pagamentos, contestações);
    // a carteira com garantia de veículo alimenta o fluxo jurídico.

    private static List<PortfolioSpec> bankPortfolios() {
        return List.of(
                new PortfolioSpec("CARTAO", "Cartão de crédito — atraso inicial", "Cartão de crédito", 22, 15, 120,
                        400, 2400, 3, "CONTRATO", false, false),
                new PortfolioSpec("VEICULO", "Financiamento de veículos", "CCB — financiamento de veículo", 16, 60, 420,
                        2500, 9000, 4, "CCB", true, true),
                new PortfolioSpec("PESSOAL", "Crédito pessoal", "Crédito pessoal", 18, 30, 720, 800, 6000, 3,
                        "CCB", true, false),
                new PortfolioSpec("CHEQUE", "Cheque especial e conta corrente", "Cheque especial", 16, 10, 360, 150,
                        2500, 1, "CONTRATO", false, false));
    }

    private static List<PortfolioSpec> retailPortfolios() {
        return List.of(
                new PortfolioSpec("CREDIARIO", "Crediário de loja", "Crediário", 22, 15, 180, 200, 1800, 4,
                        "CONFISSAO_DIVIDA", false, false),
                new PortfolioSpec("CARTAOLOJA", "Cartão da loja", "Cartão private label", 18, 20, 300, 150, 1500, 2,
                        "CONTRATO", false, false),
                new PortfolioSpec("CHEQUEPRE", "Cheques pré-datados", "Cheque", 14, 30, 240, 100, 900, 2,
                        "CHEQUE", true, false),
                new PortfolioSpec("BOLETO", "Boletos vencidos", "Duplicata", 16, 10, 150, 120, 1200, 3,
                        "DUPLICATA", true, false));
    }

    private static List<PortfolioSpec> telecomPortfolios() {
        return List.of(
                new PortfolioSpec("MOVEL", "Telefonia móvel pós-paga", "Plano móvel", 22, 10, 150, 80, 600, 3,
                        "CONTRATO", false, false),
                new PortfolioSpec("BANDALARGA", "Banda larga residencial", "Internet fixa", 18, 15, 240, 90, 700, 3,
                        "CONTRATO", false, false),
                new PortfolioSpec("EMPRESAS", "Clientes empresariais", "Plano corporativo", 14, 30, 360, 600, 4800, 3,
                        "CONTRATO", false, false),
                new PortfolioSpec("APARELHO", "Aparelhos parcelados", "Aparelho parcelado", 16, 20, 200, 150, 1500, 4,
                        "CONFISSAO_DIVIDA", false, false));
    }

    private static List<PortfolioSpec> utilityPortfolios() {
        return List.of(
                new PortfolioSpec("RESIDENCIAL", "Contas residenciais", "Fornecimento de energia", 22, 15, 180, 90, 900,
                        3, "CONTRATO", false, false),
                new PortfolioSpec("COMERCIAL", "Contas comerciais", "Fornecimento de energia — comercial", 16, 20, 300,
                        400, 4500, 3, "CONTRATO", false, false),
                new PortfolioSpec("PARCELAMENTO", "Parcelamentos quebrados", "Confissão de dívida", 16, 30, 400, 300,
                        3000, 4, "CONFISSAO_DIVIDA", true, false),
                new PortfolioSpec("RURAL", "Consumidores rurais", "Fornecimento de energia — rural", 14, 30, 360, 200,
                        2400, 2, "CONTRATO", false, false));
    }

    private static List<PortfolioSpec> educationPortfolios() {
        return List.of(
                new PortfolioSpec("MENSALIDADE", "Mensalidades da graduação", "Mensalidade", 22, 15, 240, 600, 2400, 4,
                        "CONTRATO", false, false),
                new PortfolioSpec("POS", "Pós-graduação", "Mensalidade — pós", 16, 20, 300, 500, 1800, 3, "CONTRATO",
                        false, false),
                new PortfolioSpec("ACORDOS", "Acordos anteriores quebrados", "Confissão de dívida", 16, 60, 480, 800,
                        5000, 3, "CONFISSAO_DIVIDA", true, false),
                new PortfolioSpec("EXTENSAO", "Cursos de extensão", "Curso livre", 14, 10, 180, 150, 1200, 2,
                        "CONTRATO", false, false));
    }
}
