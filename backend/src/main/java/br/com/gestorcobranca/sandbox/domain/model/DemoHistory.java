package br.com.gestorcobranca.sandbox.domain.model;

import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.CreditorSpec;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.Person;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.PortfolioSpec;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.Scope;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.Segment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Catálogo do <strong>histórico</strong> fictício do sandbox (decisões 1351–1355): 12 meses de operação registrados pela
 * carga com o relógio de demonstração. Só a assessoria {@code aurora} tem histórico (a {@code boreal} continua só com
 * o presente, para não alongar a carga). Tudo é inventado: credores com "(fictício)" no nome, pessoas com nomes
 * combinados e e-mails em {@code aurora.exemplo.test}.
 * <p>
 * Os credores do histórico têm tamanhos bem desiguais (três grandes, seis médios e uma cauda longa de pequenos) e
 * perfis econômicos diferentes, para os rankings com "Outros", o mapa de dispersão da margem nos quatro quadrantes e a
 * tendência de seis meses subindo, caindo ou estável. Os três credores do catálogo principal ficam intactos (cenários
 * do presente).
 */
public final class DemoHistory {

    /** Meses fechados de histórico antes do mês corrente (o mês corrente também tem operação até ontem). */
    public static final int MONTHS = 12;

    /** Evolução do volume ao longo do ano. */
    public enum Trend {
        /** cresce de ~60% para ~140% do volume médio */
        GROWING,
        /** cai de ~140% para ~60% */
        DECLINING,
        /** estável */
        STABLE,
        /** sazonal acentuado (educação, saúde): picos no início do ano e no meio do ano */
        SEASONAL
    }

    /**
     * Perfil econômico (quadrantes do mapa de dispersão da margem): volume alto ou baixo × margem alta ou baixa.
     */
    public enum Economics {
        /** muito recuperado com pouco esforço: rende e escala */
        SCALES,
        /** volume alto, esforço alto (muitos contatos longos): margem baixa */
        COSTLY,
        /** volume pequeno com pouco esforço: margem boa */
        EFFICIENT,
        /** pouco recuperado com muito esforço: margem negativa */
        LOSS
    }

    /**
     * @param spec          portfólio (mesmo formato do catálogo principal)
     * @param receivedMonth mês do histórico em que a carteira chega (0 = primeiro mês)
     */
    public record HistoryPortfolio(PortfolioSpec spec, int receivedMonth) {
    }

    /**
     * @param weight      escala de atividade (1 = cauda, 10 = maior credor)
     * @param assistant   credor com o assistente de IA em copiloto (turnos com custo de IA)
     */
    public record HistoryCreditor(String prefix, String legalName, String tradeName, Segment segment, Trend trend,
                                  Economics economics, int weight, boolean assistant,
                                  List<HistoryPortfolio> portfolios) {

        public CreditorSpec spec() {
            return new CreditorSpec(prefix, legalName, tradeName, segment,
                    portfolios.stream().map(HistoryPortfolio::spec).toList());
        }

        /** Primeiro mês com carteira (credor novo começa depois). */
        public int startMonth() {
            return portfolios.stream().mapToInt(HistoryPortfolio::receivedMonth).min().orElse(0);
        }
    }

    /**
     * Negociador fictício do histórico.
     *
     * @param team        código da equipe
     * @param band        faixa de custo (nome da faixa no cadastro de custos)
     * @param quality     nota média esperada da monitoria × 10 (ex.: 82 = 8,2)
     * @param fulfillment chance, em %, de o acordo fechado pela pessoa ser cumprido até o fim
     * @param caller      faz ligações pelo discador (ramal de telefonia)
     */
    public record Negotiator(String key, String name, String team, String band, int quality, int fulfillment,
                             boolean caller) {

        public Person person() {
            return new Person(key, "OPERADOR", name, Scope.ALL_CREDITORS);
        }
    }

    /**
     * @param supervisorKey pessoa (catálogo) que supervisiona a equipe
     * @param segments      segmentos de credor que a equipe atende no histórico
     */
    public record Team(String code, String name, String supervisorKey, List<Segment> segments) {
    }

    /**
     * @param supervisors supervisores adicionais (perfil SUPERVISOR, todos os credores)
     */
    public record HistorySpec(List<HistoryCreditor> creditors, List<Person> supervisors, List<Negotiator> negotiators,
                              List<Team> teams) {

        /** Pessoas novas do histórico (supervisores e negociadores), na ordem de criação. */
        public List<Person> people() {
            return Stream.concat(supervisors.stream(), negotiators.stream().map(Negotiator::person)).toList();
        }

        public int portfolioCount() {
            return creditors.stream().mapToInt(c -> c.portfolios().size()).sum();
        }

        public int debtorCount() {
            return creditors.stream().flatMap(c -> c.portfolios().stream()).mapToInt(p -> p.spec().debtors()).sum();
        }

        public List<Negotiator> team(String code) {
            return negotiators.stream().filter(n -> n.team().equals(code)).toList();
        }
    }

    /** Código da equipe que já existe no catálogo principal ("Mesa de negociação", com os operadores principais). */
    public static final String MAIN_TEAM = "MESA";

    private DemoHistory() {
    }

    public static Optional<HistorySpec> of(String tenantSlug) {
        return "aurora".equals(tenantSlug) ? Optional.of(aurora()) : Optional.empty();
    }

    // ------------------------------------------------------------------------------------------------ aurora

    private static HistorySpec aurora() {
        return new HistorySpec(auroraCreditors(),
                List.of(new Person("supervisor2", "SUPERVISOR", "Paulo Vasconcelos", Scope.ALL_CREDITORS),
                        new Person("supervisor3", "SUPERVISOR", "Juliana Mendes", Scope.ALL_CREDITORS)),
                List.of(
                        new Negotiator("negociador01", "Aline Barbosa", MAIN_TEAM, "Negociador sênior", 91, 82, true),
                        new Negotiator("negociador02", "Bruno Cardoso", MAIN_TEAM, "Negociador pleno", 78, 64, true),
                        new Negotiator("negociador03", "Cecília Dantas", MAIN_TEAM, "Negociador júnior", 84, 58, false),
                        new Negotiator("negociador04", "Diego Esteves", MAIN_TEAM, "Negociador trainee", 66, 45, false),
                        new Negotiator("negociador05", "Elisa Figueira", "BANCOS", "Negociador sênior", 88, 86, true),
                        new Negotiator("negociador06", "Fábio Guimarães", "BANCOS", "Negociador pleno", 72, 74, true),
                        new Negotiator("negociador07", "Giovana Holanda", "BANCOS", "Negociador pleno", 86, 61, false),
                        new Negotiator("negociador08", "Hugo Lacerda", "BANCOS", "Negociador júnior", 61, 52, false),
                        new Negotiator("negociador09", "Iara Macedo", "BANCOS", "Negociador trainee", 80, 48, false),
                        new Negotiator("negociador10", "Jonas Nascimento", "VAREJO", "Negociador sênior", 74, 84, true),
                        new Negotiator("negociador11", "Karina Oliveira", "VAREJO", "Negociador pleno", 93, 71, false),
                        new Negotiator("negociador12", "Leonardo Pacheco", "VAREJO", "Negociador júnior", 69, 66, false),
                        new Negotiator("negociador13", "Marina Queiroz", "VAREJO", "Negociador júnior", 82, 55, false),
                        new Negotiator("negociador14", "Nicolas Ramalho", "VAREJO", "Negociador trainee", 58, 42, false),
                        new Negotiator("negociador15", "Olívia Siqueira", "SERVICOS", "Negociador sênior", 89, 77, true),
                        new Negotiator("negociador16", "Paulo Tavares", "SERVICOS", "Negociador pleno", 76, 69, false),
                        new Negotiator("negociador17", "Sabrina Valadares", "SERVICOS", "Negociador júnior", 85, 62, false),
                        new Negotiator("negociador18", "Tiago Zanetti", "SERVICOS", "Negociador trainee", 63, 50, false)),
                List.of(new Team(MAIN_TEAM, "Mesa de negociação", "supervisor", List.of()),
                        new Team("BANCOS", "Equipe Bancos e Financeiras", "supervisor2", List.of(Segment.BANK)),
                        new Team("VAREJO", "Equipe Varejo", "supervisor3", List.of(Segment.RETAIL)),
                        new Team("SERVICOS", "Equipe Serviços e Utilidades", "supervisor", List.of(Segment.SERVICES))));
    }

    private static List<HistoryCreditor> auroraCreditors() {
        List<HistoryCreditor> list = new ArrayList<>();
        // três grandes
        list.add(creditor("MRD", "Banco Meridional S.A. (fictício)", "Banco Meridional", Segment.BANK, Trend.GROWING,
                Economics.SCALES, 10, true,
                p("CARTAO", "Cartão de crédito", "Cartão de crédito", 30, 20, 180, 400, 2600, 3, "CONTRATO", false, false, 0),
                p("PESSOAL", "Crédito pessoal", "Crédito pessoal", 26, 30, 540, 900, 6000, 3, "CCB", true, false, 0),
                p("CONSIGNADO", "Consignado privado", "Crédito consignado", 22, 30, 360, 1200, 7000, 4, "CCB", true,
                        false, 0),
                p("VEICULO", "Financiamento de veículos", "CCB — veículo", 14, 60, 420, 3000, 9500, 4, "CCB", true,
                        true, 0),
                p("CHEQUE", "Cheque especial", "Cheque especial", 12, 15, 300, 200, 2400, 1, "CONTRATO", false, false,
                        2),
                p("IMOBILIARIO", "Crédito com garantia de imóvel", "Home equity", 8, 60, 480, 5000, 12000, 3, "CCB",
                        true, false, 7)));
        list.add(creditor("ATL", "Financeira Atlântica S.A. (fictícia)", "Financeira Atlântica", Segment.BANK,
                Trend.DECLINING, Economics.COSTLY, 9, false,
                p("CDC", "Crédito direto ao consumidor", "CDC", 28, 30, 600, 500, 4500, 4, "CCB", true, false, 0),
                p("CARTAO", "Cartão de crédito", "Cartão de crédito", 24, 20, 360, 300, 2200, 3, "CONTRATO", false,
                        false, 0),
                p("PESSOAL", "Empréstimo pessoal", "Empréstimo pessoal", 18, 60, 720, 700, 5000, 3, "CCB", true,
                        false, 0),
                p("LOJISTA", "Crédito lojista", "Crédito no ponto de venda", 12, 30, 400, 300, 2500, 3, "CCB", true,
                        false, 1),
                p("CONSIGNADO", "Consignado INSS", "Crédito consignado", 10, 30, 300, 1500, 6000, 3, "CCB", true,
                        false, 3)));
        list.add(creditor("CDV", "Lojas Cidade Viva S.A. (fictícias)", "Lojas Cidade Viva", Segment.RETAIL,
                Trend.GROWING, Economics.SCALES, 9, true,
                p("CREDIARIO", "Crediário", "Crediário", 30, 15, 240, 200, 1900, 4, "CONFISSAO_DIVIDA", false, false, 0),
                p("CARTAOLOJA", "Cartão da loja", "Cartão private label", 24, 20, 300, 150, 1600, 2, "CONTRATO", false,
                        false, 0),
                p("BOLETO", "Boletos vencidos", "Duplicata", 14, 10, 150, 120, 1200, 3, "DUPLICATA", true, false, 0),
                p("CHEQUEPRE", "Cheques pré-datados", "Cheque", 10, 30, 240, 100, 900, 2, "CHEQUE", true, false, 2),
                p("MARKETPLACE", "Vendas no marketplace", "Parcelado no boleto", 10, 15, 120, 150, 1400, 3, "CONTRATO",
                        false, false, 5)));
        // seis médios
        list.add(creditor("SLN", "Saneamento Lagoa Norte S.A. (fictício)", "Saneamento Lagoa Norte",
                Segment.SERVICES, Trend.STABLE, Economics.EFFICIENT, 5, false,
                p("RESIDENCIAL", "Contas residenciais", "Água e esgoto", 10, 15, 180, 90, 900, 3, "CONTRATO", false,
                        false, 0),
                p("COMERCIAL", "Contas comerciais", "Água e esgoto — comercial", 8, 20, 300, 400, 3500, 3, "CONTRATO",
                        false, false, 0),
                p("PARCELAMENTO", "Parcelamentos quebrados", "Confissão de dívida", 8, 30, 400, 300, 3000, 3,
                        "CONFISSAO_DIVIDA", true, false, 0),
                p("RURAL", "Ligações rurais", "Água — rural", 6, 30, 360, 150, 1500, 2, "CONTRATO", false, false, 4)));
        list.add(creditor("VTL", "Vitalis Planos de Saúde S.A. (fictícia)", "Vitalis Saúde", Segment.SERVICES,
                Trend.SEASONAL, Economics.LOSS, 6, true,
                p("INDIVIDUAL", "Planos individuais", "Mensalidade de plano de saúde", 10, 30, 240, 500, 3000, 3,
                        "CONTRATO", false, false, 0),
                p("EMPRESARIAL", "Planos empresariais", "Plano empresarial", 8, 30, 300, 2000, 9000, 3, "CONTRATO",
                        false, false, 0),
                p("COPARTICIPACAO", "Coparticipação", "Coparticipação", 6, 20, 180, 150, 1200, 2, "CONTRATO", false,
                        false, 0),
                p("ODONTO", "Planos odontológicos", "Mensalidade odontológica", 5, 20, 200, 80, 600, 2, "CONTRATO",
                        false, false, 3)));
        list.add(creditor("EDP", "Faculdade Ponte Nova (fictícia)", "Faculdade Ponte Nova", Segment.SERVICES,
                Trend.SEASONAL, Economics.EFFICIENT, 5, false,
                p("GRADUACAO", "Mensalidades da graduação", "Mensalidade", 10, 15, 240, 600, 3000, 4, "CONTRATO", false,
                        false, 0),
                p("POS", "Pós-graduação", "Mensalidade — pós", 8, 20, 300, 500, 2000, 3, "CONTRATO", false, false, 0),
                p("EAD", "Cursos a distância", "Mensalidade EAD", 8, 15, 200, 150, 900, 3, "CONTRATO", false, false, 2),
                p("EXTENSAO", "Cursos de extensão", "Curso livre", 5, 10, 180, 150, 1200, 2, "CONTRATO", false, false,
                        6)));
        list.add(creditor("CRB", "Cooperativa Rural Bom Campo (fictícia)", "Coop Bom Campo", Segment.BANK,
                Trend.GROWING, Economics.EFFICIENT, 4, false,
                p("CUSTEIO", "Custeio agrícola", "Crédito de custeio", 8, 60, 400, 3000, 12000, 2, "CCB", true, false,
                        0),
                p("INVESTIMENTO", "Investimento rural", "Crédito de investimento", 6, 60, 500, 5000, 15000, 3, "CCB",
                        true, false, 1),
                p("CARTAO", "Cartão do cooperado", "Cartão de crédito", 8, 20, 240, 300, 2000, 3, "CONTRATO", false,
                        false, 0)));
        list.add(creditor("GSN", "Gás Serrano Distribuidora S.A. (fictícia)", "Gás Serrano", Segment.SERVICES,
                Trend.STABLE, Economics.COSTLY, 5, false,
                p("RESIDENCIAL", "Gás canalizado residencial", "Fornecimento de gás", 10, 15, 180, 80, 700, 3,
                        "CONTRATO", false, false, 0),
                p("INDUSTRIAL", "Clientes industriais", "Fornecimento de gás — indústria", 6, 30, 300, 3000, 12000, 3,
                        "CONTRATO", false, false, 0),
                p("PARCELAMENTO", "Parcelamentos", "Confissão de dívida", 6, 30, 400, 300, 2500, 3, "CONFISSAO_DIVIDA",
                        true, false, 0),
                p("COMERCIAL", "Clientes comerciais", "Fornecimento de gás — comércio", 6, 20, 240, 500, 4000, 3,
                        "CONTRATO", false, false, 5)));
        list.add(creditor("MVL", "Movelaria Lar Feliz Ltda (fictícia)", "Móveis Lar Feliz", Segment.RETAIL,
                Trend.DECLINING, Economics.LOSS, 5, false,
                p("CREDIARIO", "Crediário", "Crediário", 10, 30, 360, 300, 2500, 4, "CONFISSAO_DIVIDA", false, false, 0),
                p("CARNE", "Carnês", "Carnê", 8, 30, 300, 200, 1800, 4, "CONFISSAO_DIVIDA", false, false, 0),
                p("BOLETO", "Boletos", "Duplicata", 6, 20, 200, 150, 1200, 2, "DUPLICATA", true, false, 0)));
        // cauda longa
        list.add(creditor("TLC", "TeleConecta Fibra Ltda (fictícia)", "TeleConecta Fibra", Segment.SERVICES,
                Trend.GROWING, Economics.EFFICIENT, 3, false,
                p("FIBRA", "Internet por fibra", "Internet fixa", 6, 15, 180, 90, 700, 3, "CONTRATO", false, false, 6),
                p("EMPRESAS", "Clientes empresariais", "Plano corporativo", 4, 30, 240, 600, 4000, 3, "CONTRATO", false,
                        false, 7)));
        list.add(creditor("ACQ", "Academia Corpo Quente Ltda (fictícia)", "Academia Corpo Quente", Segment.SERVICES,
                Trend.SEASONAL, Economics.EFFICIENT, 1, false,
                p("MENSALIDADE", "Mensalidades", "Plano de academia", 5, 15, 180, 90, 600, 3, "CONTRATO", false, false,
                        0),
                p("PERSONAL", "Aulas particulares", "Pacote de aulas", 3, 20, 120, 200, 900, 2, "CONTRATO", false, false,
                        3)));
        list.add(creditor("OTC", "Ótica Visão Clara Ltda (fictícia)", "Ótica Visão Clara", Segment.RETAIL,
                Trend.STABLE, Economics.EFFICIENT, 1, false,
                p("CREDIARIO", "Crediário", "Crediário", 5, 20, 240, 200, 1500, 3, "CONFISSAO_DIVIDA", false, false, 0)));
        list.add(creditor("AUT", "Autopeças Estrada Real Ltda (fictícia)", "Autopeças Estrada Real", Segment.RETAIL,
                Trend.STABLE, Economics.LOSS, 2, false,
                p("EMPRESAS", "Oficinas clientes", "Duplicata", 5, 20, 240, 800, 6000, 3, "DUPLICATA", true, false, 0),
                p("BOLETO", "Boletos de balcão", "Boleto", 4, 15, 180, 150, 1200, 2, "DUPLICATA", true, false, 1)));
        list.add(creditor("ESC", "Escola Pequeno Saber Ltda (fictícia)", "Escola Pequeno Saber", Segment.SERVICES,
                Trend.SEASONAL, Economics.EFFICIENT, 2, false,
                p("MENSALIDADE", "Mensalidades", "Mensalidade escolar", 6, 15, 240, 500, 2400, 4, "CONTRATO", false,
                        false, 0),
                p("MATERIAL", "Material didático", "Material escolar", 3, 20, 180, 150, 800, 2, "CONTRATO", false,
                        false, 2)));
        list.add(creditor("CLN", "Clínica Bem Viver Ltda (fictícia)", "Clínica Bem Viver", Segment.SERVICES,
                Trend.DECLINING, Economics.LOSS, 2, false,
                p("PARTICULAR", "Atendimentos particulares", "Serviços médicos", 4, 30, 300, 200, 2500, 2, "CONTRATO",
                        false, false, 0)));
        list.add(creditor("CNS", "Consórcio Rota Segura S.A. (fictício)", "Consórcio Rota Segura", Segment.BANK,
                Trend.STABLE, Economics.EFFICIENT, 2, false,
                p("COTAS", "Cotas em atraso", "Consórcio", 5, 30, 360, 800, 5000, 3, "CONTRATO", true, false, 0),
                p("VEICULO", "Cotas contempladas", "Consórcio — veículo", 4, 60, 420, 3000, 9000, 3, "CONTRATO", true,
                        true, 2)));
        list.add(creditor("IMB", "Imobiliária Porto Lar Ltda (fictícia)", "Imobiliária Porto Lar", Segment.SERVICES,
                Trend.STABLE, Economics.EFFICIENT, 2, false,
                p("ALUGUEL", "Aluguéis", "Aluguel residencial", 5, 15, 180, 900, 4000, 3, "CONTRATO", true, false, 0),
                p("CONDOMINIO", "Condomínios", "Taxa condominial", 4, 20, 240, 300, 1800, 3, "CONTRATO", true, false,
                        1)));
        list.add(creditor("FRM", "Farmácia Saúde Já Ltda (fictícia)", "Farmácia Saúde Já", Segment.RETAIL,
                Trend.STABLE, Economics.EFFICIENT, 1, false,
                p("CONVENIO", "Convênios empresa", "Compras no convênio", 4, 15, 150, 100, 800, 2, "CONTRATO", false,
                        false, 0),
                p("DELIVERY", "Entregas a prazo", "Venda a prazo", 3, 10, 120, 80, 500, 2, "CONTRATO", false, false,
                        5)));
        list.add(creditor("MAT", "Materiais de Construção Alicerce Ltda (fictícia)", "Alicerce Materiais",
                Segment.RETAIL, Trend.GROWING, Economics.COSTLY, 3, false,
                p("CREDIARIO", "Crediário", "Crediário", 5, 20, 240, 300, 2500, 3, "CONFISSAO_DIVIDA", false, false, 0),
                p("EMPRESAS", "Construtoras clientes", "Duplicata", 4, 30, 300, 2000, 9000, 3, "DUPLICATA", true,
                        false, 2)));
        list.add(creditor("SEG", "Seguradora Amparo S.A. (fictícia)", "Seguradora Amparo", Segment.SERVICES,
                Trend.STABLE, Economics.EFFICIENT, 1, false,
                p("PREMIOS", "Prêmios em atraso", "Seguro residencial", 4, 20, 180, 150, 1200, 2, "CONTRATO", false,
                        false, 0),
                p("AUTO", "Seguro auto", "Seguro auto", 3, 20, 180, 400, 2500, 2, "CONTRATO", false, false, 4)));
        list.add(creditor("PET", "Pet Center Amigo Fiel Ltda (fictício)", "Pet Center Amigo Fiel", Segment.RETAIL,
                Trend.GROWING, Economics.EFFICIENT, 1, false,
                p("CREDIARIO", "Crediário", "Crediário", 3, 15, 120, 100, 800, 2, "CONFISSAO_DIVIDA", false, false, 8)));
        list.add(creditor("TVA", "TV Assinatura Nuvem Ltda (fictícia)", "TV Nuvem", Segment.SERVICES,
                Trend.DECLINING, Economics.COSTLY, 2, false,
                p("ASSINATURA", "Assinaturas", "TV por assinatura", 6, 15, 240, 80, 600, 3, "CONTRATO", false, false, 0),
                p("EQUIPAMENTO", "Equipamentos não devolvidos", "Equipamento", 3, 30, 300, 300, 900, 1, "CONTRATO",
                        false, false, 0)));
        list.add(creditor("CRD", "Credifácil Microcrédito S.A. (fictício)", "Credifácil", Segment.BANK,
                Trend.GROWING, Economics.SCALES, 3, false,
                p("MICRO", "Microcrédito produtivo", "Microcrédito", 6, 30, 360, 500, 4000, 3, "CCB", true, false, 0),
                p("CAPITALGIRO", "Capital de giro", "Capital de giro", 4, 30, 360, 3000, 12000, 3, "CCB", true, false,
                        3)));
        list.add(creditor("ENE", "Energia Sol Nascente Ltda (fictícia)", "Sol Nascente Energia", Segment.SERVICES,
                Trend.GROWING, Economics.EFFICIENT, 2, false,
                p("SOLAR", "Sistemas solares financiados", "Energia solar", 4, 30, 240, 2000, 9000, 3, "CONTRATO", true,
                        false, 4)));
        list.add(creditor("UNI", "Uniformes Brasil Ltda (fictícia)", "Uniformes Brasil", Segment.RETAIL,
                Trend.STABLE, Economics.LOSS, 1, false,
                p("EMPRESAS", "Empresas clientes", "Duplicata", 4, 30, 300, 1000, 6000, 3, "DUPLICATA", true, false,
                        0)));
        list.add(creditor("HOS", "Hospital Santa Clara (fictício)", "Hospital Santa Clara", Segment.SERVICES,
                Trend.STABLE, Economics.LOSS, 2, false,
                p("PARTICULAR", "Internações particulares", "Serviços hospitalares", 4, 30, 360, 2000, 12000, 3,
                        "CONTRATO", false, false, 0),
                p("PLANO", "Glosas de plano", "Coparticipação hospitalar", 3, 30, 300, 300, 2500, 2, "CONTRATO", false,
                        false, 1)));
        list.add(creditor("AGR", "Agropecuária Vale Verde Ltda (fictícia)", "Agro Vale Verde", Segment.RETAIL,
                Trend.SEASONAL, Economics.EFFICIENT, 1, false,
                p("INSUMOS", "Insumos agrícolas", "Duplicata", 4, 30, 360, 1500, 9000, 3, "DUPLICATA", true, false,
                        0)));
        return List.copyOf(list);
    }

    private static HistoryCreditor creditor(String prefix, String legalName, String tradeName, Segment segment,
                                            Trend trend, Economics economics, int weight, boolean assistant,
                                            HistoryPortfolio... portfolios) {
        return new HistoryCreditor(prefix, legalName, tradeName, segment, trend, economics, weight, assistant,
                List.of(portfolios));
    }

    private static HistoryPortfolio p(String suffix, String name, String product, int debtors, int minDaysOverdue,
                                      int maxDaysOverdue, int minPrincipal, int maxPrincipal, int maxTitles,
                                      String instrumentType, boolean executive, boolean vehicleCollateral,
                                      int receivedMonth) {
        return new HistoryPortfolio(new PortfolioSpec(suffix, name, product, debtors, minDaysOverdue, maxDaysOverdue,
                minPrincipal, maxPrincipal, maxTitles, instrumentType, executive, vehicleCollateral), receivedMonth);
    }
}
