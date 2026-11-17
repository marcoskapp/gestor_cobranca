package br.com.gestorcobranca.sandbox.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.PromiseInput;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.debt.application.port.in.InsolvencyUseCases;
import br.com.gestorcobranca.debt.application.port.in.InsolvencyUseCases.RegisterProceedingCommand;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.ClientInfo;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.LoginCommand;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.LoginResult;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases.GoalCommand;
import br.com.gestorcobranca.incentive.application.port.in.LoggedTimeMaintenance;
import br.com.gestorcobranca.incentive.application.port.in.PeriodUseCases;
import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases;
import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases.CriterionInput;
import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases.EvaluationCommand;
import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases.FormCommand;
import br.com.gestorcobranca.incentive.application.port.in.StatementUseCases;
import br.com.gestorcobranca.incentive.application.port.in.TierUseCases;
import br.com.gestorcobranca.incentive.application.port.in.TierUseCases.RoundSummary;
import br.com.gestorcobranca.incentive.application.port.in.TierUseCases.SettingsCommand;
import br.com.gestorcobranca.incentive.application.port.in.TierUseCases.SettingsView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.InstallmentView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationScheduling;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.ContractView;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases;
import br.com.gestorcobranca.sandbox.application.SandboxProperties;
import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import br.com.gestorcobranca.sandbox.application.service.HistoryOperations.Fate;
import br.com.gestorcobranca.sandbox.application.service.HistoryOperations.HistoryCase;
import br.com.gestorcobranca.sandbox.application.service.HistoryOperations.PlannedAgreement;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.Segment;
import br.com.gestorcobranca.sandbox.domain.model.DemoHistory;
import br.com.gestorcobranca.sandbox.domain.model.DemoHistory.Economics;
import br.com.gestorcobranca.sandbox.domain.model.DemoHistory.HistoryCreditor;
import br.com.gestorcobranca.sandbox.domain.model.DemoHistory.HistoryPortfolio;
import br.com.gestorcobranca.sandbox.domain.model.DemoHistory.Negotiator;
import br.com.gestorcobranca.sandbox.domain.model.DemoPortfolioData.Debt;
import br.com.gestorcobranca.sandbox.domain.service.CnjNumbers;
import br.com.gestorcobranca.sandbox.domain.service.FictitiousData;
import br.com.gestorcobranca.sandbox.domain.service.HistoryPlanner;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Histórico fictício de 12 meses da assessoria (decisões 1351–1355), registrado pelos casos de uso com o relógio de
 * demonstração deslocado dia a dia: as carteiras do histórico chegam mês a mês (importação via API), os negociadores
 * contatam, negociam e fecham acordos (cumpridos, quebrados, cancelados), os devedores pagam (pagamento informado e
 * aprovado), mensagens saem por template e são respondidas no chat — inclusive durante ligações —, metas do mês, monitoria,
 * fechamento da remuneração variável, classificação por desempenho, custas jurídicas e uma recuperação judicial. No
 * último mês, as sessões dos negociadores geram o tempo logado. Ao terminar (ou falhar), o relógio volta ao presente.
 * <p>
 * Volume e perfil vêm do catálogo ({@link DemoHistory}) e das regras puras do {@link HistoryPlanner}; os sorteios usam
 * semente fixa (a mesma carga produz o mesmo histórico). Idempotência: cada mês fechado na remuneração variável é
 * dispensado numa nova subida; ações com chave (contato, pagamento, mensagem) não se repetem.
 */
@SandboxSeedComponent
class HistorySeeder {

    private static final Logger LOG = LoggerFactory.getLogger(HistorySeeder.class);
    /** Dias de operação registrados por mês (o tempo logado cobre todos os dias úteis da janela final). */
    private static final int OPERATING_DAYS = 8;
    /** Dias úteis finais com sessões dos negociadores (tempo logado). */
    private static final int LOGGED_DAYS = 20;
    private static final Duration REFRESH_EVERY = Duration.ofMinutes(20);
    private static final ClientInfo CLIENT = new ClientInfo("10.0.0.10", "Sandbox (histórico fictício)");
    private static final String FORM_NAME = "Monitoria de atendimento (sandbox)";

    private final OrganizationSeeder organization;
    private final PortfolioLoadSeeder portfolioLoad;
    private final LegalSeeder legal;
    private final HistoryOperations ops;
    private final ServiceContractUseCases contracts;
    private final DebtorUseCases debtors;
    private final CaseUseCases cases;
    private final CaseWorkUseCases caseWork;
    private final NegotiationScheduling negotiationDaily;
    private final CommissionPlanUseCases plans;
    private final PeriodUseCases periods;
    private final StatementUseCases statements;
    private final QualityUseCases quality;
    private final TierUseCases tiers;
    private final AuthenticationUseCases authentication;
    private final LoggedTimeMaintenance loggedTime;
    private final InsolvencyUseCases insolvency;
    private final SandboxProperties properties;
    private final SeedRuntime rt;

    HistorySeeder(OrganizationSeeder organization, PortfolioLoadSeeder portfolioLoad, LegalSeeder legal,
                  HistoryOperations ops, ServiceContractUseCases contracts, DebtorUseCases debtors, CaseUseCases cases,
                  CaseWorkUseCases caseWork, NegotiationScheduling negotiationDaily, CommissionPlanUseCases plans,
                  PeriodUseCases periods, StatementUseCases statements, QualityUseCases quality, TierUseCases tiers,
                  AuthenticationUseCases authentication, LoggedTimeMaintenance loggedTime,
                  InsolvencyUseCases insolvency, SandboxProperties properties, SeedRuntime rt) {
        this.organization = organization;
        this.portfolioLoad = portfolioLoad;
        this.legal = legal;
        this.ops = ops;
        this.contracts = contracts;
        this.debtors = debtors;
        this.cases = cases;
        this.caseWork = caseWork;
        this.negotiationDaily = negotiationDaily;
        this.plans = plans;
        this.periods = periods;
        this.statements = statements;
        this.quality = quality;
        this.tiers = tiers;
        this.authentication = authentication;
        this.loggedTime = loggedTime;
        this.insolvency = insolvency;
        this.properties = properties;
        this.rt = rt;
    }

    /** Estado do histórico em memória (só durante a carga). */
    private static final class State {
        final Map<String, List<HistoryCase>> casesByCreditor = new LinkedHashMap<>();
        final List<PlannedAgreement> agreements = new ArrayList<>();
        final Map<String, UUID> lastInteraction = new HashMap<>();
        final Map<String, String> refreshTokens = new HashMap<>();
        final Map<String, Instant> lastRefresh = new HashMap<>();
        YearMonth nextToClose;
        UUID formId;
        int sequence;
    }

    void seed(TenantSeed seed) {
        if (!seed.hasHistory()) {
            return;
        }
        YearMonth first = YearMonth.from(seed.historyStart);
        YearMonth lastClosed = YearMonth.from(seed.today).minusMonths(1);
        if (historyDone(seed, lastClosed)) {
            LOG.atInfo().addKeyValue("tenantId", seed.tenantId).log("Histórico do sandbox já registrado; nada a fazer");
            return;
        }
        State st = new State();
        st.nextToClose = first;
        List<LocalDate> logged = loggedWindow(seed.today);
        try {
            rt.travelTo(seed.historyOrigin().plusMinutes(30));
            setup(seed, st);
            for (int m = 0; m <= DemoHistory.MONTHS; m++) {
                YearMonth month = first.plusMonths(m);
                if (m < DemoHistory.MONTHS && closed(seed, month)) {
                    st.nextToClose = month.plusMonths(1);
                    receivePortfolios(seed, st, m);
                    continue;
                }
                rt.phase(seed.spec.slug() + ": histórico " + month);
                month(seed, st, m, month, logged);
            }
            rt.advance(seed.today.minusDays(1).atTime(19, 0));
            closePending(seed, st);
        } finally {
            rt.backToPresent();
            rt.flushHistory();
        }
        // tempo logado do período das sessões (a sincronização agendada só refaz ontem e hoje)
        if (!logged.isEmpty()) {
            rt.event("tempo logado do histórico", () -> rt.system(seed.tenantId,
                    () -> loggedTime.resyncLoggedTime(logged.getFirst(), logged.getLast())));
        }
    }

    // ------------------------------------------------------------------------------------------------ preparação

    /** Formulário de monitoria e parâmetros da classificação por desempenho (amostras menores que o padrão). */
    private void setup(TenantSeed seed, State st) {
        Actor gestor = seed.actor("gestor");
        Actor qualidade = seed.actor("qualidade");
        st.formId = rt.as(qualidade, quality::forms).stream().filter(f -> FORM_NAME.equals(f.name())).findFirst()
                .map(QualityUseCases.FormView::id)
                .orElseGet(() -> rt.as(qualidade, () -> quality.createForm(new FormCommand(FORM_NAME,
                        "Roteiro de monitoria das ligações e conversas (fictício)", List.of(
                        new CriterionInput("ABERTURA", "Identificação e confirmação do titular", new BigDecimal("1"), true),
                        new CriterionInput("CLAREZA", "Clareza sobre valores e condições", new BigDecimal("2"), false),
                        new CriterionInput("POSTURA", "Respeito e linguagem adequada (CDC)", new BigDecimal("2"), true),
                        new CriterionInput("REGISTRO", "Registro e tabulação corretos", new BigDecimal("1"), false)))))
                        .id());
        rt.event("parâmetros da classificação por desempenho", () -> {
            SettingsView current = rt.as(gestor, tiers::settings);
            if (!current.customized()) {
                rt.as(gestor, () -> tiers.updateSettings(new SettingsCommand(90, 3, 10, new BigDecimal("7.0"),
                        current.matrix(), "B", "MONTHLY", 3)));
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ mês

    private void month(TenantSeed seed, State st, int index, YearMonth month, List<LocalDate> logged) {
        FictitiousData random = new FictitiousData(seed.spec.seed() * 101 + index);
        List<LocalDate> operating = HistoryPlanner.operatingDays(month, seed.today, OPERATING_DAYS);
        TreeSet<LocalDate> days = new TreeSet<>(operating);
        logged.stream().filter(d -> YearMonth.from(d).equals(month)).forEach(days::add);
        if (days.isEmpty()) {
            return;
        }
        MonthPlan plan = plan(seed, index, month, operating.size(), random);
        for (LocalDate day : days) {
            boolean op = operating.contains(day);
            int dayIndex = operating.indexOf(day);
            TreeMap<LocalTime, List<Runnable>> agenda = new TreeMap<>();
            if (op) {
                at(agenda, LocalTime.of(8, 5), () -> morning(seed, st, day));
                at(agenda, LocalTime.of(8, 30), () -> payments(seed, st, day, random));
                if (dayIndex == 0) {
                    at(agenda, LocalTime.of(9, 10), () -> monthStart(seed, st, index, month, random));
                }
                at(agenda, LocalTime.of(10, 0), () -> agreements(seed, st, plan.agreements(dayIndex), day, random));
                at(agenda, LocalTime.of(11, 30), () -> messages(seed, st, plan.messages(dayIndex), month, random));
                at(agenda, LocalTime.of(14, 0), () -> replies(seed, st, random));
                if (plan.calls.contains(dayIndex)) {
                    at(agenda, LocalTime.of(15, 0), () -> call(seed, st, plan.callCreditor(dayIndex), random));
                }
                at(agenda, LocalTime.of(17, 35), () -> contacts(seed, st, plan.contacts(dayIndex), day, random));
                if (dayIndex == operating.size() - 1) {
                    at(agenda, LocalTime.of(17, 55), () -> monitoring(seed, st, index, day, random));
                    at(agenda, LocalTime.of(18, 10), () -> specialEvents(seed, st, index, day));
                }
            }
            if (logged.contains(day)) {
                sessions(seed, st, day, agenda);
            }
            for (Map.Entry<LocalTime, List<Runnable>> entry : agenda.entrySet()) {
                rt.advance(day.atTime(entry.getKey()));
                entry.getValue().forEach(Runnable::run);
            }
        }
    }

    private static void at(TreeMap<LocalTime, List<Runnable>> agenda, LocalTime time, Runnable action) {
        agenda.computeIfAbsent(time, t -> new ArrayList<>()).add(action);
    }

    /** Quantidades do mês por credor, repartidas pelos dias de operação. */
    private record MonthPlan(Map<Integer, List<String>> agreementsByDay, Map<Integer, List<String>> messagesByDay,
                             Map<Integer, List<String>> contactsByDay, List<Integer> calls, List<String> callCreditors) {

        List<String> agreements(int day) {
            return agreementsByDay.getOrDefault(day, List.of());
        }

        List<String> messages(int day) {
            return messagesByDay.getOrDefault(day, List.of());
        }

        List<String> contacts(int day) {
            return contactsByDay.getOrDefault(day, List.of());
        }

        String callCreditor(int day) {
            return callCreditors.get(calls.indexOf(day));
        }
    }

    private MonthPlan plan(TenantSeed seed, int index, YearMonth month, int days, FictitiousData random) {
        Map<Integer, List<String>> agreements = new HashMap<>();
        Map<Integer, List<String>> messages = new HashMap<>();
        Map<Integer, List<String>> contacts = new HashMap<>();
        if (days == 0) {
            return new MonthPlan(agreements, messages, contacts, List.of(), List.of());
        }
        for (HistoryCreditor c : seed.history.creditors()) {
            double factor = HistoryPlanner.factor(c, index, DemoHistory.MONTHS, month.getMonth());
            spread(agreements, c.prefix(), HistoryPlanner.count(c.weight() * HistoryPlanner.agreementsPerWeight(
                    c.economics()) * factor, random), days, random);
            spread(messages, c.prefix(), HistoryPlanner.count(c.weight() * 0.6 * factor, random), days, random);
            spread(contacts, c.prefix(), HistoryPlanner.count(c.weight() * HistoryPlanner.interactionsPerWeight(
                    c.economics()) * factor, random), days, random);
        }
        // três ligações por mês, uma em cada grande credor ativo, em dias diferentes
        List<Integer> calls = new ArrayList<>();
        List<String> callCreditors = new ArrayList<>();
        int slot = 1;
        for (String prefix : List.of("MRD", "ATL", "CDV")) {
            if (slot < days) {
                calls.add(slot);
                callCreditors.add(prefix);
                slot += Math.max(1, days / 3);
            }
        }
        return new MonthPlan(agreements, messages, contacts, calls, callCreditors);
    }

    private static void spread(Map<Integer, List<String>> byDay, String prefix, int count, int days,
                               FictitiousData random) {
        for (int i = 0; i < count; i++) {
            byDay.computeIfAbsent(random.between(0, days - 1), d -> new ArrayList<>()).add(prefix);
        }
    }

    // ------------------------------------------------------------------------------------------------ blocos do dia

    /** Rotina diária do negotiation (parcelas vencidas, quebras) e fechamento dos meses que já podem ser fechados. */
    private void morning(TenantSeed seed, State st, LocalDate day) {
        rt.event("rotina diária de acordos (" + day + ")", () -> rt.system(seed.tenantId, () -> {
            negotiationDaily.runDailyMaintenance();
            return null;
        }));
        closePending(seed, st);
    }

    /** Parcelas que vencem até hoje: pagas conforme o desfecho planejado; cancelamentos antes da 1ª parcela. */
    private void payments(TenantSeed seed, State st, LocalDate day, FictitiousData random) {
        Instant now = rt.now();
        for (PlannedAgreement a : st.agreements) {
            if (a.fate == Fate.CANCEL) {
                if (a.paid == 0 && a.formalizedOn.isBefore(day)) {
                    rt.event("acordo cancelado do histórico (" + a.target.ref() + ")", () -> ops.cancel(seed, a));
                    a.paid = -1;
                    a.target.agreement = null;
                }
                continue;
            }
            if (a.paid < 0 || a.finishedPaying()) {
                continue;
            }
            for (InstallmentView installment : a.installments) {
                if (a.finishedPaying() || installment.number() <= a.paid) {
                    continue;
                }
                if (installment.dueDate().isAfter(day)) {
                    break;
                }
                Instant paidAt = installment.dueDate().isBefore(day)
                        ? installment.dueDate().atTime(LocalTime.of(10, 0)).atZone(TimeConfig.BUSINESS_ZONE).toInstant()
                        : now.minus(Duration.ofMinutes(20));
                boolean pix = random.chance(60);
                rt.event("pagamento do histórico (" + a.target.ref() + ")",
                        () -> ops.pay(seed, a, installment, paidAt, pix));
                a.paid++;
            }
            if (a.finishedPaying()) {
                if (a.fate == Fate.KEEP) {
                    a.target.closed = true;
                }
                a.target.agreement = null;
            }
        }
        st.agreements.removeIf(a -> a.paid < 0 || a.finishedPaying());
    }

    /** Início do mês: carteiras que chegam, metas dos negociadores, distribuição das filas e classificação. */
    private void monthStart(TenantSeed seed, State st, int index, YearMonth month, FictitiousData random) {
        receivePortfolios(seed, st, index);
        goals(seed, month);
        Actor supervisor = seed.actor("supervisor");
        for (String prefix : List.of("MRD", "ATL", "CDV")) {
            UUID queueId = seed.queues.get(prefix);
            if (queueId != null) {
                rt.event("distribuição da fila " + prefix + " (" + month + ")", Set.of("queue.no_members",
                        "queue.no_cases"), () -> rt.as(supervisor, () -> caseWork.distribute(queueId, 200)));
                refreshAssignees(seed, st, prefix);
            }
        }
        if (index >= DemoHistory.MONTHS - 3) {
            tierRound(seed, index == DemoHistory.MONTHS);
        }
    }

    /** Acordos do dia: contato efetivo e acordo pelo negociador; o desfecho segue o perfil de quem fechou. */
    private void agreements(TenantSeed seed, State st, List<String> prefixes, LocalDate day, FictitiousData random) {
        for (String prefix : prefixes) {
            HistoryCase c = pick(st, prefix, random, true);
            if (c == null) {
                continue;
            }
            Negotiator closer = negotiatorFor(seed, c, random);
            int installments = installments(c.creditor, random);
            String channel = c.conversationId != null && random.chance(40) ? "WHATSAPP" : "PHONE";
            rt.event("acordo do histórico (" + c.ref() + ")", () -> {
                Duration talk = Duration.ofMinutes(random.between(4, 9));
                Instant start = rt.now().minus(talk).minus(Duration.ofMinutes(random.between(1, 5)));
                if ("PHONE".equals(channel)) {
                    ops.interaction(seed, closer.key(), c, "PHONE", "CPC_NEGOTIATING",
                            "Devedor aceitou negociar e pediu as condições", start, talk, null,
                            "sbx-hist-cpc-" + c.ref() + "-" + day);
                }
                c.assigneeKey = closer.key();
                AgreementView agreement = ops.agreement(seed, closer.key(), c, installments, channel,
                        "sbx-hist-aceite-" + c.ref() + "-" + day);
                double keep = closer.fulfillment() / 100.0 * HistoryPlanner.fulfillmentModifier(c.creditor.economics());
                Fate fate;
                int toPay;
                int n = agreement.installments().size();
                if (random.between(0, 99) < 5) {
                    fate = Fate.CANCEL;
                    toPay = 0;
                } else if (random.between(0, 999) < keep * 1000) {
                    fate = Fate.KEEP;
                    toPay = n;
                } else {
                    fate = Fate.BREAK;
                    toPay = n <= 1 ? 0 : random.between(0, n - 1);
                }
                PlannedAgreement planned = new PlannedAgreement(agreement.id(), closer.key(), c, fate, toPay,
                        agreement.installments(), day);
                c.agreement = planned;
                // quebra: o caso volta a negociar depois que a regra de quebra alcança a 1ª parcela não paga
                c.busyUntil = switch (fate) {
                    case KEEP -> null;
                    case CANCEL -> day.plusDays(3);
                    case BREAK -> agreement.installments().get(Math.min(toPay, n - 1)).dueDate().plusDays(45);
                };
                st.agreements.add(planned);
            });
        }
    }

    /** Mensagens do dia por template (WhatsApp, SMS e, de vez em quando, e-mail). */
    private void messages(TenantSeed seed, State st, List<String> prefixes, YearMonth month, FictitiousData random) {
        for (String prefix : prefixes) {
            HistoryCase c = pick(st, prefix, random, false);
            if (c == null) {
                continue;
            }
            Negotiator sender = owner(seed, c, random);
            int kind = random.between(0, 99);
            String key = "sbx-hist-msg-" + c.ref() + "-" + rt.today() + "-" + (st.sequence++);
            if (kind < 70 && c.debt.debtor().whatsapp()) {
                rt.event("mensagem de WhatsApp do histórico", () -> ops.sendTemplate(seed, sender.key(), c, "WHATSAPP",
                        "COBRANCA_ABERTURA", Map.of("nome", c.firstName()), key));
            } else if (kind < 95 || c.debt.debtor().email() == null) {
                rt.event("SMS do histórico", () -> ops.sendTemplate(seed, sender.key(), c, "SMS",
                        "COBRANCA_RETOMADA_SMS", Map.of("nome", c.firstName()), key));
            } else {
                BigDecimal balance = c.debt.contract().titles().stream().map(t -> t.principal())
                        .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_EVEN);
                long overdue = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(
                        c.debt.contract().titles().getFirst().dueDate(), rt.today()));
                rt.event("e-mail do histórico", () -> ops.sendTemplate(seed, sender.key(), c, "EMAIL",
                        "COBRANCA_SALDO_EMAIL", Map.of("nome", c.firstName(), "credor", c.creditor.tradeName(),
                                "saldo", balance.toPlainString(), "dias", String.valueOf(overdue)), key));
            }
        }
    }

    /** Respostas de devedores às mensagens de WhatsApp e resposta escrita do negociador (tempo de chat). */
    private void replies(TenantSeed seed, State st, FictitiousData random) {
        List<HistoryCase> waiting = st.casesByCreditor.values().stream().flatMap(List::stream)
                .filter(c -> c.conversationId != null && !c.closed).toList();
        int replies = Math.min(waiting.size(), random.between(2, 5));
        for (int i = 0; i < replies; i++) {
            HistoryCase c = waiting.get(random.between(0, waiting.size() - 1));
            Negotiator agent = owner(seed, c, random);
            String text = random.pick(List.of("Oi, quero saber o valor para quitar.", "Boa tarde, consigo parcelar?",
                    "Recebi a mensagem. Podem me ligar amanhã?", "Qual o desconto à vista?"));
            rt.event("resposta do devedor no WhatsApp", () -> {
                ops.inbound(seed, c, text + " (fictício)");
                // o assistente (copiloto) lê a mensagem antes da resposta do negociador: sugestão com custo de IA
                rt.awaitQuiet();
                rt.advance(rt.now().plus(Duration.ofSeconds(random.between(70, 170))));
                ops.chatReply(seed, agent.key(), c, "Olá, " + c.firstName() + "! Vou verificar as condições e já "
                        + "retorno por aqui. (histórico fictício)", "sbx-hist-chat-" + c.ref() + "-" + (st.sequence++));
            });
        }
    }

    /** Ligação do dia num grande credor; no meio dela, o negociador responde no chat de outro credor. */
    private void call(TenantSeed seed, State st, String prefix, FictitiousData random) {
        HistoryCase c = null;
        Negotiator found = null;
        for (int attempt = 0; attempt < 15 && found == null; attempt++) {
            c = pick(st, prefix, random, false);
            found = c == null ? null : callerFor(seed, c);
        }
        if (found == null) {
            return;
        }
        Negotiator caller = found;
        HistoryCase target = c;
        HistoryCase chat = st.casesByCreditor.entrySet().stream().filter(e -> !e.getKey().equals(prefix))
                .flatMap(e -> e.getValue().stream()).filter(x -> x.conversationId != null && !x.closed
                        && caller.key().equals(x.conversationOwner)).findFirst().orElse(null);
        Duration talk = Duration.ofSeconds(random.between(150, 420));
        Duration wrapUp = Duration.ofSeconds(random.between(30, 100));
        String tabulation = random.pick(List.of("CPC_NEGOTIATING", "CPC_NEGOTIATING", "REFUSED", "THIRD_PARTY"));
        rt.event("ligação do histórico (" + target.ref() + ")", () -> ops.call(seed, caller.key(), target, talk, wrapUp,
                tabulation, "Ligação pelo discador; devedor atendeu", chat == null ? null : at -> {
                    ops.inbound(seed, chat, "Estou no aguardo da proposta. (fictício)");
                    rt.advance(rt.now().plus(Duration.ofSeconds(60)));
                    ops.chatReply(seed, caller.key(), chat, "Já vou te enviar, " + chat.firstName()
                            + ". (histórico fictício)", "sbx-hist-chat-call-" + chat.ref() + "-" + (st.sequence++));
                }, "sbx-hist-call-" + target.ref() + "-" + rt.today()));
    }

    /** Contatos do dia (tentativas e conversas), com início espalhado pelo expediente de cada negociador. */
    private void contacts(TenantSeed seed, State st, List<String> prefixes, LocalDate day, FictitiousData random) {
        Map<String, LocalDateTime> cursor = new HashMap<>();
        for (String prefix : prefixes) {
            HistoryCase c = pick(st, prefix, random, false);
            if (c == null) {
                continue;
            }
            Negotiator who = negotiatorFor(seed, c, random);
            int[] minutes = HistoryPlanner.contactMinutes(c.creditor.economics());
            Duration duration = Duration.ofMinutes(random.between(minutes[0], minutes[1]))
                    .plusSeconds(random.between(0, 59));
            LocalDateTime start = cursor.getOrDefault(who.key(), day.atTime(9, random.between(0, 40)));
            if (start.plus(duration).isAfter(day.atTime(17, 30))) {
                continue;
            }
            cursor.put(who.key(), start.plus(duration).plusMinutes(random.between(3, 25)));
            int roll = random.between(0, 99);
            String tabulation = roll < 32 ? "NO_ANSWER" : roll < 42 ? "VOICEMAIL" : roll < 47 ? "BUSY"
                    : roll < 57 ? "THIRD_PARTY" : roll < 82 ? "CPC_NEGOTIATING" : roll < 92 ? "REFUSED" : "PROMISE";
            boolean effective = Set.of("CPC_NEGOTIATING", "REFUSED", "PROMISE").contains(tabulation);
            Duration real = effective ? duration : Duration.ofSeconds(random.between(25, 90));
            PromiseInput promise = "PROMISE".equals(tabulation) ? new PromiseInput(c.debt.contract().titles().getFirst()
                    .principal().multiply(new BigDecimal("0.3")).setScale(0, RoundingMode.DOWN).setScale(2,
                            RoundingMode.UNNECESSARY), day.plusDays(random.between(3, 10))) : null;
            String note = switch (tabulation) {
                case "REFUSED" -> "Devedor disse que não tem condições de pagar agora";
                case "CPC_NEGOTIATING" -> "Conversa sobre a dívida; devedor vai avaliar as condições";
                case "PROMISE" -> "Prometeu pagar parte do saldo";
                case "THIRD_PARTY" -> "Recado deixado com familiar";
                default -> "Tentativa de contato";
            };
            Instant startedAt = start.atZone(TimeConfig.BUSINESS_ZONE).toInstant();
            rt.event("contato do histórico (" + c.ref() + ")", () -> {
                UUID id = ops.interaction(seed, who.key(), c, "PHONE", tabulation, note, startedAt, real, promise,
                        "sbx-hist-" + c.ref() + "-" + day + "-" + (st.sequence++));
                c.assigneeKey = who.key();
                if (id != null) {
                    st.lastInteraction.put(who.key(), id);
                }
            });
        }
    }

    /** Monitoria do mês: uma avaliação por negociador (duas nos últimos meses), em torno da nota média da pessoa. */
    private void monitoring(TenantSeed seed, State st, int index, LocalDate day, FictitiousData random) {
        int perPerson = index >= DemoHistory.MONTHS - 4 ? 2 : 1;
        for (Negotiator n : seed.history.negotiators()) {
            for (int e = 0; e < perPerson; e++) {
                // a monitoria (todos) e o supervisor da equipe da pessoa (escopo de desempenho da equipe)
                String evaluator = e == 0 ? "qualidade" : seed.history.teams().stream()
                        .filter(t -> t.code().equals(n.team())).findFirst().orElseThrow().supervisorKey();
                Map<String, BigDecimal> scores = new LinkedHashMap<>();
                boolean critical = n.quality() < 65 && random.chance(12);
                for (String code : List.of("ABERTURA", "CLAREZA", "POSTURA", "REGISTRO")) {
                    int value = Math.max(0, Math.min(100, n.quality() + random.between(-14, 12)));
                    scores.put(code, BigDecimal.valueOf(value, 1));
                }
                if (critical) {
                    scores.put("POSTURA", BigDecimal.ZERO);
                }
                UUID reference = e == 0 ? st.lastInteraction.get(n.key()) : null;
                LocalDate evaluatedOn = day.minusDays(e);
                rt.event("monitoria de " + n.key(), Set.of("quality_evaluation.already_evaluated"),
                        () -> rt.as(seed.actor(evaluator), () -> quality.evaluate(new EvaluationCommand(st.formId,
                                seed.user(n.key()), reference == null ? "OTHER" : "INTERACTION", reference,
                                evaluatedOn, Map.copyOf(scores), critical
                                        ? "Linguagem inadequada com o devedor (fictício)"
                                        : "Atendimento conferido na monitoria (fictício)"))));
            }
        }
    }

    /** Fatos especiais: custas jurídicas adiantadas pela assessoria e uma recuperação judicial (atenção especial). */
    private void specialEvents(TenantSeed seed, State st, int index, LocalDate day) {
        if (index == 3 || index == 6 || index == 9) {
            String prefix = index == 6 ? "CNS" : "MRD";
            String code = prefix + "-VEICULO";
            st.casesByCreditor.getOrDefault(prefix, List.of()).stream()
                    .filter(c -> c.portfolioCode.equals(code) && !c.closed && c.agreement == null).findFirst()
                    .ifPresent(c -> rt.event("caso jurídico do histórico (" + c.ref() + ")", () -> {
                        CaseView view = rt.system(seed.tenantId, () -> cases.get(c.caseId));
                        if (legal.openMatter(seed, view).isEmpty()) {
                            legal.fileLawsuit(seed, view, 50 + index, day, "AGENCY", false);
                        }
                        c.closed = true;
                    }));
        }
        if (index == 7) {
            st.casesByCreditor.getOrDefault("GSN", List.of()).stream()
                    .filter(c -> c.portfolioCode.equals("GSN-INDUSTRIAL") && c.agreement == null).findFirst()
                    .ifPresent(c -> rt.event("recuperação judicial do histórico", () -> {
                        if (rt.as(seed.actor("gestor"), () -> insolvency.isUnderInsolvency(c.debtorId))) {
                            return;
                        }
                        String cnj = CnjNumbers.format(Math.floorMod(seed.spec.seed() * 31, 9_000_000L) + 1_000_000L,
                                day.getYear(), 8, 26, 100);
                        rt.as(seed.actor("gestor"), () -> insolvency.register(new RegisterProceedingCommand(c.debtorId,
                                "JUDICIAL_RECOVERY", cnj, "1ª Vara de Falências e Recuperações Judiciais (fictícia)",
                                day.minusDays(12), "Informado pelo representante da empresa no atendimento (fictício)")));
                        c.closed = true;
                    }));
        }
    }

    // ------------------------------------------------------------------------------------------------ pessoas

    private void goals(TenantSeed seed, YearMonth month) {
        Actor gestor = seed.actor("gestor");
        Set<UUID> withGoal = rt.as(gestor, () -> plans.goals(month)).stream()
                .map(CommissionPlanUseCases.GoalView::subjectId).collect(Collectors.toSet());
        String[] unit = {null};
        for (Negotiator n : seed.history.negotiators()) {
            UUID userId = seed.user(n.key());
            if (withGoal.contains(userId)) {
                continue;
            }
            if (unit[0] == null) {
                List<String> units = rt.as(gestor, () -> plans.goalUnitOptions(month, "USER", userId)).units();
                unit[0] = units.isEmpty() ? "CURRENCY" : units.getFirst();
            }
            BigDecimal base = switch (n.band()) {
                case "Negociador sênior" -> new BigDecimal("36000");
                case "Negociador pleno" -> new BigDecimal("26000");
                case "Negociador júnior" -> new BigDecimal("16000");
                default -> new BigDecimal("9000");
            };
            BigDecimal target = "CURRENCY".equals(unit[0]) ? base.setScale(2, RoundingMode.UNNECESSARY)
                    : base.divide(new BigDecimal("600"), 0, RoundingMode.HALF_UP);
            rt.event("meta de " + n.key() + " (" + month + ")", () -> rt.as(gestor, () -> plans.setGoal(
                    new GoalCommand(month, "USER", userId, target, unit[0]))));
        }
    }

    /** Fecha, em ordem, os meses do histórico cuja carência terminou (gera os extratos da remuneração variável). */
    private void closePending(TenantSeed seed, State st) {
        YearMonth current = YearMonth.from(rt.today());
        Actor gestor = seed.actor("gestor");
        while (st.nextToClose.isBefore(current)) {
            YearMonth month = st.nextToClose;
            try {
                rt.as(gestor, () -> periods.close(month));
                LOG.atInfo().addKeyValue("period", month).log("Remuneração variável do histórico fechada");
            } catch (DomainException e) {
                if ("commission_period.grace_pending".equals(e.code())) {
                    return;
                }
                if (!"commission_period.already_closed".equals(e.code())) {
                    rt.warn("fechamento da remuneração variável " + month + ": " + e.code());
                }
            }
            st.nextToClose = month.plusMonths(1);
            approveStatements(seed, month);
        }
    }

    /** Extratos dos meses mais antigos aprovados (os recentes ficam calculados, para a aprovação na demonstração). */
    private void approveStatements(TenantSeed seed, YearMonth month) {
        if (!month.isBefore(YearMonth.from(seed.today).minusMonths(2))) {
            return;
        }
        // o gestor fecha e o financeiro aprova (segregação de funções)
        Actor financeiro = seed.actor("financeiro");
        rt.event("aprovação dos extratos " + month, () -> rt.as(financeiro, () -> statements.list(month, null,
                "CALCULATED", PageQuery.of(0, 100))).content().forEach(s -> rt.event("extrato " + month,
                () -> rt.as(financeiro, () -> statements.approve(s.id())))));
    }

    /** Rodada de classificação apurada agora e publicada pelo gestor (a do mês corrente fica em revisão). */
    private void tierRound(TenantSeed seed, boolean leaveForReview) {
        Actor gestor = seed.actor("gestor");
        rt.event("classificação por desempenho", Set.of("tier_round.open_exists"), () -> {
            RoundSummary round = rt.as(gestor, tiers::assessNow);
            if (!leaveForReview) {
                rt.as(gestor, () -> tiers.publish(round.id()));
            }
        });
    }

    /** Sessões dos negociadores (dois turnos de 6h20) com renovação a cada 25 min — base do tempo logado. */
    private void sessions(TenantSeed seed, State st, LocalDate day, TreeMap<LocalTime, List<Runnable>> agenda) {
        List<Negotiator> all = seed.history.negotiators();
        for (int i = 0; i < all.size(); i++) {
            Negotiator n = all.get(i);
            LocalTime start = i % 2 == 0 ? LocalTime.of(8, 55) : LocalTime.of(11, 40);
            LocalTime end = start.plusHours(6).plusMinutes(20);
            at(agenda, start, () -> login(seed, st, n));
            for (LocalTime t = start.plus(REFRESH_EVERY); !t.isAfter(end); t = t.plus(REFRESH_EVERY)) {
                at(agenda, t, () -> refresh(seed, st, n));
            }
        }
    }

    private void login(TenantSeed seed, State st, Negotiator n) {
        rt.event("sessão de " + n.key(), () -> {
            LoginResult result = authentication.login(new LoginCommand(seed.spec.slug(), n.person().email(seed.spec),
                    properties.seed().demoPassword(), CLIENT));
            if (result.refreshToken() != null) {
                st.refreshTokens.put(n.key(), result.refreshToken());
            }
        });
    }

    /**
     * Renova a sessão; se ela expirou por inatividade (um bloco longo do dia — ligação, respostas no chat — passou dos
     * 30 min entre duas renovações), o negociador entra de novo, como faria na tela.
     */
    private void refresh(TenantSeed seed, State st, Negotiator n) {
        String token = st.refreshTokens.get(n.key());
        if (token == null) {
            return;
        }
        try {
            LoginResult result = authentication.refresh(token, CLIENT);
            st.refreshTokens.put(n.key(), result.refreshToken());
        } catch (RuntimeException e) {
            st.refreshTokens.remove(n.key());
            login(seed, st, n);
        }
    }

    // ------------------------------------------------------------------------------------------------ carteiras e casos

    /** Carteiras do histórico que chegam no mês: cadastro, importação via API e casos abertos. */
    private void receivePortfolios(TenantSeed seed, State st, int index) {
        Map<String, List<Debt>> arriving = new LinkedHashMap<>();
        Map<String, HistoryCreditor> owner = new HashMap<>();
        for (HistoryCreditor creditor : seed.history.creditors()) {
            for (HistoryPortfolio p : creditor.portfolios()) {
                if (p.receivedMonth() != index) {
                    continue;
                }
                String code = p.spec().code(creditor.spec());
                UUID creditorId = seed.creditors.get(creditor.prefix());
                ContractView contract = rt.as(seed.actor("admin"), () -> contracts.listByCreditor(creditorId)).stream()
                        .filter(v -> seed.contracts.get(creditor.prefix()).equals(v.id())).findFirst().orElseThrow();
                organization.portfolio(seed, creditorId, contract, creditor.spec(), p.spec(),
                        seed.receivedOn(p.receivedMonth()));
                arriving.put(code, seed.data.historyByPortfolio().get(code));
                owner.put(code, creditor);
            }
        }
        if (arriving.isEmpty()) {
            return;
        }
        portfolioLoad.load(seed, arriving, rt.today());
        for (Map.Entry<String, List<Debt>> entry : arriving.entrySet()) {
            HistoryCreditor creditor = owner.get(entry.getKey());
            UUID creditorId = seed.creditors.get(creditor.prefix());
            UUID portfolioId = seed.portfolios.get(entry.getKey());
            List<HistoryCase> list = st.casesByCreditor.computeIfAbsent(creditor.prefix(), k -> new ArrayList<>());
            for (Debt debt : entry.getValue()) {
                rt.system(seed.tenantId, () -> debtors.findByDocument(debt.debtor().document())
                        .flatMap(d -> cases.findActive(d.id(), portfolioId).map(c -> new HistoryCase(creditor,
                                entry.getKey(), creditorId, portfolioId, d.id(), c.id(), debt))))
                        .ifPresent(list::add);
            }
        }
    }

    /** Responsáveis dos casos do credor depois da distribuição (só negociadores do histórico atendem). */
    private void refreshAssignees(TenantSeed seed, State st, String prefix) {
        List<HistoryCase> list = st.casesByCreditor.getOrDefault(prefix, List.of());
        if (list.isEmpty()) {
            return;
        }
        Map<UUID, String> keyByUser = new HashMap<>();
        seed.history.negotiators().forEach(n -> keyByUser.put(seed.user(n.key()), n.key()));
        Map<UUID, UUID> assignee = new HashMap<>();
        UUID creditorId = list.getFirst().creditorId;
        for (int page = 0; ; page++) {
            int p = page;
            var result = rt.system(seed.tenantId, () -> cases.search(new CaseUseCases.CaseFilter(creditorId, null, null,
                    null, null, null, null, null), PageQuery.of(p, 200)));
            result.content().forEach(v -> assignee.put(v.id(), v.assigneeUserId()));
            if ((long) (page + 1) * 200 >= result.totalElements()) {
                break;
            }
        }
        for (HistoryCase c : list) {
            UUID user = assignee.get(c.caseId);
            c.assigneeKey = user == null ? null : keyByUser.get(user);
            if (user != null && c.assigneeKey == null) {
                c.closed = true; // responsável fora do histórico: não atende pelo histórico
            }
        }
    }

    /** Caso de cobrança do credor, sem acordo em vigor quando for negociar. */
    private HistoryCase pick(State st, String prefix, FictitiousData random, boolean negotiable) {
        LocalDate today = rt.today();
        List<HistoryCase> list = st.casesByCreditor.getOrDefault(prefix, List.of()).stream()
                .filter(c -> !c.closed && (!negotiable || c.available(today))).toList();
        return list.isEmpty() ? null : list.get(random.between(0, list.size() - 1));
    }

    /** Negociador que atende o caso: o responsável pela distribuição ou alguém da equipe do segmento (ou da mesa). */
    private Negotiator negotiatorFor(TenantSeed seed, HistoryCase c, FictitiousData random) {
        // caso distribuído: só o responsável trabalha; conversa iniciada: segue com quem a abriu
        String key = c.assigneeKey != null ? c.assigneeKey : c.conversationOwner;
        if (key != null) {
            String k = key;
            return seed.history.negotiators().stream().filter(n -> n.key().equals(k)).findFirst()
                    .orElseThrow();
        }
        List<Negotiator> team = teamFor(seed, c.creditor.segment());
        if (random.chance(25)) {
            team = seed.history.team(DemoHistory.MAIN_TEAM);
        }
        // quem trabalha o caso primeiro passa a ser o dono dele (o collection não deixa outro operador agir)
        Negotiator chosen = team.get(random.between(0, team.size() - 1));
        c.assigneeKey = chosen.key();
        return chosen;
    }

    /** Quem fala com o devedor por mensagem: o dono da conversa já aberta (ou o negociador do caso). */
    private Negotiator owner(TenantSeed seed, HistoryCase c, FictitiousData random) {
        if (c.conversationOwner != null) {
            return seed.history.negotiators().stream().filter(n -> n.key().equals(c.conversationOwner)).findFirst()
                    .orElseThrow();
        }
        return negotiatorFor(seed, c, random);
    }

    private Negotiator callerFor(TenantSeed seed, HistoryCase c) {
        if (c.assigneeKey != null) {
            return seed.history.negotiators().stream().filter(n -> n.key().equals(c.assigneeKey) && n.caller())
                    .findFirst().orElse(null);
        }
        return teamFor(seed, c.creditor.segment()).stream().filter(Negotiator::caller).findFirst()
                .orElseGet(() -> seed.history.team(DemoHistory.MAIN_TEAM).stream().filter(Negotiator::caller)
                        .findFirst().orElse(null));
    }

    private static List<Negotiator> teamFor(TenantSeed seed, Segment segment) {
        return seed.history.teams().stream().filter(t -> t.segments().contains(segment)).findFirst()
                .map(t -> seed.history.team(t.code())).orElseGet(() -> seed.history.team(DemoHistory.MAIN_TEAM));
    }

    private static int installments(HistoryCreditor creditor, FictitiousData random) {
        if (random.chance(30)) {
            return 1;
        }
        return switch (creditor.segment()) {
            case BANK -> random.between(2, 6);
            case RETAIL -> random.between(2, 4);
            case SERVICES -> random.between(2, 5);
        };
    }

    // ------------------------------------------------------------------------------------------------ apoio

    /** Mês do histórico já fechado na remuneração variável (carga anterior chegou até ele). */
    /** Ver {@link HistoryPlanner#historyDone}. */
    private boolean historyDone(TenantSeed seed, YearMonth lastClosed) {
        Map<YearMonth, PeriodUseCases.PeriodView> byMonth = rt.as(seed.actor("gestor"), periods::list).stream()
                .collect(Collectors.toMap(PeriodUseCases.PeriodView::period, p -> p, (a, b) -> a));
        PeriodUseCases.PeriodView last = byMonth.get(lastClosed);
        PeriodUseCases.PeriodView previous = byMonth.get(lastClosed.minusMonths(1));
        return HistoryPlanner.historyDone(last != null && "CLOSED".equals(last.status()),
                last == null ? null : last.closeableFrom(),
                previous != null && "CLOSED".equals(previous.status()), seed.today);
    }

    private boolean closed(TenantSeed seed, YearMonth month) {
        return rt.as(seed.actor("gestor"), periods::list).stream()
                .anyMatch(p -> month.equals(p.period()) && "CLOSED".equals(p.status()));
    }

    /** Últimos dias úteis antes de hoje com sessões dos negociadores. */
    private static List<LocalDate> loggedWindow(LocalDate today) {
        List<LocalDate> days = HistoryPlanner.businessDays(today.minusDays(40), today.minusDays(1));
        return days.subList(Math.max(0, days.size() - LOGGED_DAYS), days.size());
    }

}
