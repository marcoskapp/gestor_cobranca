package br.com.gestorcobranca.sandbox.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorRequestCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeView;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.identity.application.port.in.CreditorPortalAdministration;
import br.com.gestorcobranca.identity.application.port.in.CreditorPortalAdministration.CreditorUserView;
import br.com.gestorcobranca.identity.application.port.in.CreditorPortalAdministration.InviteCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases;
import br.com.gestorcobranca.sandbox.application.SandboxProperties;
import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import br.com.gestorcobranca.sandbox.application.port.out.ActivationMailbox;
import br.com.gestorcobranca.sandbox.application.port.out.CreditorMfaVault;
import br.com.gestorcobranca.sandbox.application.port.out.CreditorPortalClient;
import br.com.gestorcobranca.sandbox.application.port.out.CreditorPortalClient.CallFailedException;
import br.com.gestorcobranca.sandbox.application.port.out.CreditorPortalClient.Document;
import br.com.gestorcobranca.sandbox.application.port.out.CreditorPortalClient.PaymentReport;
import br.com.gestorcobranca.sandbox.application.port.out.CreditorPortalClient.Session;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.CreditorPerson;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.CreditorPortalSpec;
import br.com.gestorcobranca.sandbox.domain.model.DemoPortfolioData.Debt;
import br.com.gestorcobranca.sandbox.domain.service.DemoPdfBytes;
import br.com.gestorcobranca.sandbox.domain.service.DemoTotp;
import br.com.gestorcobranca.settlement.application.port.in.SettlementExportUseCases;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.CreateStatementCommand;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.SendCommand;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.StatementFilter;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.StatementView;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Portal do credor de demonstração (fatia 8): liberação, convites e perfis pelos casos de uso da assessoria; prestação
 * de contas e pedido ao credor para o Início ter "Aguardando você"; e as pessoas do credor ativadas e agindo pela
 * <strong>própria API HTTP do portal</strong>, com sessão real (RS-83).
 * <p>
 * MFA pela opção D: o segredo de cada pessoa é o aleatório que o servidor devolve no {@code activation/start} (o QR que
 * a pessoa leria), guardado no {@link CreditorMfaVault} do volume do sandbox; a carga calcula o código pelo RFC 6238
 * ({@link DemoTotp}) e nunca usa segredo fixo, endpoint de código ou atalho na identidade. Toda ação da pessoa do credor
 * roda só no tempo real e cada código usa um passo de 30 s novo para aquela pessoa (o servidor recusa o reuso e conta
 * como falha). As ações ficam com pessoas diferentes: o Financeiro aceita um demonstrativo, o Jurídico responde a uma
 * contestação e a Gestão informa um pagamento — que o Financeiro da assessoria recusa com o motivo para o credor
 * (RS-86). Ao fim, as sessões abertas pela carga são encerradas e nenhuma pessoa fica com convite pendente (RS-87).
 * <p>
 * O log técnico só leva contagens e duração ({@code step=creditor_portal}); nunca e-mail, segredo, código, link, token
 * ou id de sessão.
 */
@SandboxSeedComponent
class CreditorPortalSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(CreditorPortalSeeder.class);
    /** Entre as ativações: o limite por IP das rotas de autenticação do credor é de 20 por minuto por operação. */
    private static final Duration ACTIVATION_SPACING = Duration.ofSeconds(7);
    /** Documento fictício (PDF válido de uma página, só texto): o portal confere o conteúdo e recusa PDF ilegível. */
    private static final byte[] DEMO_PDF = DemoPdfBytes.of("Documento ficticio do sandbox - portal do credor");
    /** Devedor da carteira principal do 1º credor cuja contestação recebe o pedido respondido pelo portal. */
    private static final int ANSWERED_DISPUTE_DEBT = 6;
    /** Devedores da carteira principal sem cenário, candidatos ao título do pagamento informado pelo credor. */
    private static final int FIRST_FREE_DEBT = 14;

    private final CreditorPortalAdministration portal;
    private final SettlementStatementUseCases statements;
    private final SettlementExportUseCases exports;
    private final DisputeUseCases disputes;
    private final DebtorUseCases debtors;
    private final CaseUseCases cases;
    private final PaymentUseCases payments;
    private final ActivationMailbox mailbox;
    private final CreditorMfaVault vault;
    private final CreditorPortalClient client;
    private final SeedRuntime rt;
    private final SandboxProperties properties;

    CreditorPortalSeeder(CreditorPortalAdministration portal, SettlementStatementUseCases statements,
                         SettlementExportUseCases exports, DisputeUseCases disputes, DebtorUseCases debtors,
                         CaseUseCases cases, PaymentUseCases payments, ActivationMailbox mailbox, CreditorMfaVault vault,
                         CreditorPortalClient client, SeedRuntime rt, SandboxProperties properties) {
        this.portal = portal;
        this.statements = statements;
        this.exports = exports;
        this.disputes = disputes;
        this.debtors = debtors;
        this.cases = cases;
        this.payments = payments;
        this.mailbox = mailbox;
        this.vault = vault;
        this.client = client;
        this.rt = rt;
        this.properties = properties;
    }

    // ===================================================================================== etapa 1: assessoria

    /**
     * Pela assessoria: portal liberado e pessoas convidadas (Gestor, com {@code CREDITOR_PORTAL_MANAGE}); demonstrativos
     * do 1º credor — um enviado com o relógio de demonstração 14 dias atrás (prazo de 10 dias vencido), outro enviado
     * hoje (o Financeiro do credor aceita no portal) e o seguinte só calculado (a tela interna); e o pedido ao credor numa
     * contestação aberta (o Jurídico do credor responde no portal).
     */
    void prepare(TenantSeed seed) {
        Actor gestor = seed.actor("gestor");
        int invited = 0;
        for (CreditorPortalSpec spec : DemoCatalog.creditorPortal(seed.spec.slug())) {
            UUID creditorId = seed.creditorId(spec.creditorIndex());
            if (!rt.as(gestor, () -> portal.status(creditorId)).enabled()) {
                rt.as(gestor, () -> portal.enable(creditorId));
            }
            List<CreditorUserView> existing = rt.as(gestor, () -> portal.status(creditorId)).users();
            for (CreditorPerson person : spec.people()) {
                if (existing.stream().noneMatch(u -> u.email().equalsIgnoreCase(person.email()))) {
                    rt.as(gestor, () -> portal.invite(creditorId, new InviteCommand(person.name() + " (fictícia)",
                            person.email(), person.role())));
                    invited++;
                }
            }
        }
        rt.optional("demonstrativos do portal do credor", () -> statementChain(seed));
        rt.optional("pedido ao credor respondido pelo portal", () -> disputeRequest(seed));
        LOG.atInfo().addKeyValue("step", "creditor_portal").addKeyValue("phase", "prepare")
                .addKeyValue("tenant", seed.spec.slug()).addKeyValue("invited", invited)
                .log("Portal do credor do sandbox liberado");
    }

    private void statementChain(TenantSeed seed) {
        Actor financeiro = seed.actor("financeiro");
        UUID creditorId = seed.creditorId(0);
        long existing = rt.as(financeiro, () -> statements.search(new StatementFilter(creditorId, null),
                PageQuery.of(0, 1))).totalElements();
        if (existing > 0) {
            return; // carga anterior (ou demonstrativo aberto à mão): a cadeia não se repete
        }
        YearMonth month = YearMonth.from(seed.today);
        YearMonth first = month.minusMonths(3);
        // 1º: enviado há 14 dias (prazo de 10 dias vencido), pelo relógio de demonstração
        rt.inThePast(seed.today.minusDays(14).atTime(LocalTime.of(10, 0)),
                () -> sent(seed, creditorId, first.atDay(1), first.atEndOfMonth()));
        // 2º: enviado hoje, aceito pelo Financeiro do credor no portal; 3º: só calculado (tela interna)
        sent(seed, creditorId, first.plusMonths(1).atDay(1), first.plusMonths(1).atEndOfMonth());
        UUID third = rt.as(financeiro, () -> statements.create(new CreateStatementCommand(creditorId,
                first.plusMonths(2).atDay(1), first.plusMonths(2).atEndOfMonth()))).id();
        rt.as(financeiro, () -> statements.calculate(third));
    }

    /** Calculado e revisado pelo financeiro, aprovado pelo gestor (segregação), arquivos gerados e enviado pelo portal. */
    private void sent(TenantSeed seed, UUID creditorId, LocalDate start, LocalDate end) {
        Actor financeiro = seed.actor("financeiro");
        UUID id = rt.as(financeiro, () -> statements.create(new CreateStatementCommand(creditorId, start, end))).id();
        rt.as(financeiro, () -> statements.calculate(id));
        rt.as(financeiro, () -> statements.review(id));
        rt.as(seed.actor("gestor"), () -> statements.approve(id));
        UUID csv = rt.as(financeiro, () -> exports.export(id, "CSV")).id();
        UUID pdf = rt.as(financeiro, () -> exports.export(id, "PDF")).id();
        rt.as(financeiro, () -> statements.send(id, new SendCommand("PORTAL", null, List.of(csv, pdf))));
    }

    private void disputeRequest(TenantSeed seed) {
        Optional<UUID> dispute = answeredDispute(seed);
        if (dispute.isEmpty()) {
            return;
        }
        Actor supervisor = seed.actor("supervisor");
        UUID id = dispute.get();
        if (rt.as(supervisor, () -> disputes.creditorRequests(id)).isEmpty()) {
            var draft = rt.as(supervisor, () -> disputes.draftCreditorRequest(id));
            rt.as(supervisor, () -> disputes.requestCreditor(id, new CreditorRequestCommand(draft.subject(), draft.body(),
                    null, Set.of(), Set.of(), false)));
        }
    }

    /** Contestação aberta (cenário "já pagou") do devedor da carteira principal do 1º credor. */
    private Optional<UUID> answeredDispute(TenantSeed seed) {
        Debt debt = seed.debt(0, 0, ANSWERED_DISPUTE_DEBT);
        UUID portfolioId = seed.portfolioId(0, 0);
        Actor supervisor = seed.actor("supervisor");
        return rt.system(seed.tenantId, () -> debtors.findByDocument(debt.debtor().document())
                        .flatMap(d -> cases.findActive(d.id(), portfolioId)))
                .flatMap(c -> rt.as(supervisor, () -> disputes.listByCase(c.id())).stream()
                        .filter(d -> "OPEN".equals(d.status()) || "UNDER_REVIEW".equals(d.status()))
                        .map(DisputeView::id).findFirst());
    }

    // ===================================================================================== etapa 2: pessoas do credor

    /** Pessoa do credor nesta execução: sessão real aberta pelo servidor e o último passo do TOTP já usado. */
    private static final class Person {
        final CreditorPerson spec;
        final UUID creditorId;
        String secret;
        Session session;
        long lastStep;

        Person(CreditorPerson spec, UUID creditorId, long lastStep) {
            this.spec = spec;
            this.creditorId = creditorId;
            this.lastStep = lastStep;
        }
    }

    /**
     * Ativa as pessoas convidadas pelo fluxo real (link do Mailpit → {@code activation/start} → código →
     * {@code activation/complete}) e faz as ações do credor pela API do portal. Só no tempo real.
     */
    void act(TenantSeed seed) {
        if (rt.shifted()) {
            // o relógio deslocável é global: sessão e código do credor só com a hora real (RS-84)
            throw new IllegalStateException("ações da pessoa do credor só no tempo real");
        }
        long started = System.nanoTime();
        // nesta execução, o primeiro código de cada pessoa espera o próximo passo: um passo já usado antes (carga
        // anterior, script) nunca é reapresentado
        long startStep = DemoTotp.step(rt.realNow());
        Map<String, Person> people = new LinkedHashMap<>();
        int activated = 0;
        int actions = 0;
        try {
            for (CreditorPortalSpec spec : DemoCatalog.creditorPortal(seed.spec.slug())) {
                UUID creditorId = seed.creditorId(spec.creditorIndex());
                for (CreditorPerson p : spec.people()) {
                    Person person = new Person(p, creditorId, startStep);
                    people.put(p.key(), person);
                    String scenario = "ativação (" + p.role() + ", credor " + spec.creditorIndex() + ")";
                    if (attempt(scenario, () -> activate(seed, person))) {
                        activated++;
                    }
                }
            }
            actions += attempt("aceite do demonstrativo", () -> acknowledgeStatement(seed, people)) ? 1 : 0;
            actions += attempt("resposta à contestação", () -> respondDispute(seed, people)) ? 1 : 0;
            actions += attempt("pagamento informado", () -> reportAndRejectPayment(seed, people)) ? 1 : 0;
        } finally {
            // sessões abertas pela carga são encerradas (RS-87); a pessoa entra de novo pelo portal
            for (Person person : people.values()) {
                if (person.session != null) {
                    try {
                        client.logout(person.session);
                    } catch (RuntimeException e) {
                        LOG.atWarn().addKeyValue("step", "creditor_portal").log("Saída da sessão do credor falhou");
                    }
                    person.session = null;
                }
            }
        }
        verifyFinalState(seed);
        LOG.atInfo().addKeyValue("step", "creditor_portal").addKeyValue("phase", "act")
                .addKeyValue("tenant", seed.spec.slug()).addKeyValue("people", people.size())
                .addKeyValue("activated", activated).addKeyValue("actions", actions)
                .addKeyValue("durationMs", Duration.ofNanos(System.nanoTime() - started).toMillis())
                .log("Pessoas do credor do sandbox ativadas e ações feitas pelo portal");
    }

    /** @return true se a pessoa foi ativada agora */
    private boolean activate(TenantSeed seed, Person person) {
        Actor gestor = seed.actor("gestor");
        CreditorUserView view = user(seed, person);
        if ("ACTIVE".equals(view.status())) {
            person.secret = vault.secret(seed.spec.slug(), person.spec.email()).orElse(null);
            if (person.secret == null) {
                rt.warn("portal do credor: pessoa ativa sem segredo no cofre do sandbox (" + person.spec.role()
                        + "); use \"Refazer acesso\" na ficha do credor ou reset.sh");
            }
            return false;
        }
        if (!"INVITED".equals(view.status())) {
            return false; // revogada à mão: fica como está
        }
        String token = invitationToken(seed, person, view);
        CreditorPortalClient.Enrollment enrollment;
        try {
            enrollment = client.startActivation(token);
        } catch (CallFailedException e) {
            // link velho (convite reenviado numa carga interrompida): um convite novo, como a assessoria faria
            Instant before = rt.realNow();
            rt.as(gestor, () -> portal.resendInvitation(person.creditorId, view.id()));
            token = awaitToken(person, before);
            enrollment = client.startActivation(token);
        }
        // guarda antes de concluir: o segredo pendente é o mesmo numa nova tentativa e a pessoa nunca fica ativa sem
        // o segredo no cofre
        person.secret = enrollment.secret();
        vault.store(new CreditorMfaVault.Entry(seed.spec.slug(), person.spec.email(), person.spec.role(), person.secret));
        String invitation = token;
        person.session = client.completeActivation(invitation, properties.seed().demoPassword(), code(person));
        pause(ACTIVATION_SPACING);
        return true;
    }

    private String invitationToken(TenantSeed seed, Person person, CreditorUserView view) {
        Instant invitedAt = view.invitedAt() == null ? Instant.EPOCH : view.invitedAt();
        try {
            return mailbox.awaitCredentialToken(person.spec.email(), invitedAt, Duration.ofSeconds(60));
        } catch (IllegalStateException e) {
            Instant before = rt.realNow();
            rt.as(seed.actor("gestor"), () -> portal.resendInvitation(person.creditorId, view.id()));
            return awaitToken(person, before);
        }
    }

    private String awaitToken(Person person, Instant sentAfter) {
        return mailbox.awaitCredentialToken(person.spec.email(), sentAfter, properties.seed().activationTimeout());
    }

    /** Sessão real da pessoa: a da ativação ou um login completo (senha + código num passo novo). */
    private Optional<Session> session(TenantSeed seed, Person person) {
        if (person.session == null && person.secret != null) {
            person.session = client.login(seed.spec.slug(), person.spec.email(), properties.seed().demoPassword(),
                    code(person));
        }
        return Optional.ofNullable(person.session);
    }

    /**
     * Código do autenticador num passo de 30 s maior que o último usado por esta pessoa (o servidor recusa o reuso e o
     * conta como falha): espera a virada quando preciso. Chamado a cada tentativa (429 → código novo, RS-89).
     */
    private Supplier<String> code(Person person) {
        return () -> {
            if (rt.shifted()) {
                throw new IllegalStateException("código do autenticador só no tempo real");
            }
            while (DemoTotp.step(rt.realNow()) <= person.lastStep) {
                pause(Duration.ofSeconds(DemoTotp.secondsLeft(rt.realNow())).plusMillis(300));
            }
            long step = DemoTotp.step(rt.realNow());
            person.lastStep = step;
            return DemoTotp.code(person.secret, step);
        };
    }

    private Optional<Person> byRole(Map<String, Person> people, UUID creditorId, String role) {
        return people.values().stream().filter(p -> p.creditorId.equals(creditorId) && p.spec.role().equals(role))
                .filter(p -> p.secret != null).findFirst();
    }

    /** Financeiro do credor aceita, com o código, o demonstrativo enviado hoje (os arquivos que viu, pelo SHA-256). */
    private boolean acknowledgeStatement(TenantSeed seed, Map<String, Person> people) {
        UUID creditorId = seed.creditorId(0);
        List<StatementView> sentList = rt.as(seed.actor("financeiro"), () -> statements.search(
                new StatementFilter(creditorId, "SENT"), PageQuery.of(0, 10))).content();
        if (sentList.size() < 2) {
            return false; // o mais antigo fica aguardando (prazo vencido); sem o segundo, nada a aceitar
        }
        StatementView newest = sentList.stream().max(Comparator.comparing(StatementView::sequenceNumber)).orElseThrow();
        Optional<Person> financeiro = byRole(people, creditorId, "FINANCEIRO");
        return financeiro.flatMap(p -> session(seed, p).map(s -> {
            List<String> hashes = client.statementFileHashes(s, newest.id());
            client.acknowledgeStatement(s, newest.id(), "Conferido pelo financeiro do credor (demonstração fictícia)",
                    hashes, code(p));
            return true;
        })).orElse(false);
    }

    /** Jurídico do credor responde ao pedido da assessoria, com um documento fictício (passa pela quarentena). */
    private boolean respondDispute(TenantSeed seed, Map<String, Person> people) {
        Optional<UUID> dispute = answeredDispute(seed);
        Optional<Person> juridico = byRole(people, seed.creditorId(0), "JURIDICO");
        if (dispute.isEmpty() || juridico.isEmpty()) {
            return false;
        }
        Session s = session(seed, juridico.get()).orElse(null);
        if (s == null) {
            return false;
        }
        Optional<UUID> request = client.awaitingRequest(s, dispute.get());
        if (request.isEmpty()) {
            return false; // já respondido numa carga anterior
        }
        client.respondDispute(s, dispute.get(), request.get(), "CONFIRMS_DEBT",
                "O pagamento citado pelo devedor não consta nos nossos registros; segue o extrato (texto fictício).",
                new Document("extrato-ficticio.pdf", "application/pdf", DEMO_PDF));
        return true;
    }

    /**
     * Gestão do credor informa um pagamento direto (com o código); o Financeiro da assessoria o recusa com o motivo
     * que o credor vê (o informe pelo portal é sempre revisado). Recusado nos últimos 30 dias, aparece no "Aguardando
     * você".
     */
    private boolean reportAndRejectPayment(TenantSeed seed, Map<String, Person> people) {
        Optional<Person> gestao = byRole(people, seed.creditorId(0), "GESTOR");
        if (gestao.isEmpty()) {
            return false;
        }
        Session s = session(seed, gestao.get()).orElse(null);
        if (s == null) {
            return false;
        }
        List<CreditorPortalClient.Item> reported = client.reportedPayments(s);
        boolean did = false;
        if (reported.isEmpty()) {
            Optional<String> title = freeTitle(seed, s);
            if (title.isEmpty()) {
                rt.warn("portal do credor: nenhum título livre para o pagamento informado de demonstração");
                return false;
            }
            UUID id = client.reportPayment(s, new PaymentReport(List.of(title.get()), new BigDecimal("50.00"),
                            seed.today.minusDays(2), "TRANSFER", "SBX-CREDOR-TED-1",
                            "Pagamento recebido direto na conta do credor (cenário fictício)"),
                    new Document("comprovante-ficticio.pdf", "application/pdf", DEMO_PDF),
                    "sandbox-" + seed.spec.slug() + "-credor-pagamento-1", code(gestao.get()));
            reported = List.of(new CreditorPortalClient.Item(id, "IN_REVIEW"));
            did = true;
        }
        for (CreditorPortalClient.Item item : reported) {
            if ("IN_REVIEW".equals(item.status())) {
                rt.optional("recusa do pagamento informado pelo credor", () -> rt.as(seed.actor("financeiro"),
                        // motivo para o credor = código da lista fechada (o texto da nota fica só na assessoria)
                        () -> payments.reject(item.id(), "Comprovante não confere com o extrato da conta (fictício)",
                                "PROOF_INVALID")));
            }
        }
        return did;
    }

    private Optional<String> freeTitle(TenantSeed seed, Session s) {
        String code = seed.portfolioCode(0, 0);
        List<Debt> debts = seed.data.debts(code);
        for (int i = FIRST_FREE_DEBT; i < debts.size(); i++) {
            for (var title : debts.get(i).contract().titles()) {
                if (client.titleAvailable(s, title.externalId())) {
                    return Optional.of(title.externalId());
                }
            }
        }
        return Optional.empty();
    }

    // ===================================================================================== conferência final

    /** Nenhum convite pendente nem pessoa bloqueada ao fim da carga (RS-84, RS-87): vira aviso. */
    private void verifyFinalState(TenantSeed seed) {
        Actor gestor = seed.actor("gestor");
        List<String> problems = new ArrayList<>();
        for (CreditorPortalSpec spec : DemoCatalog.creditorPortal(seed.spec.slug())) {
            UUID creditorId = seed.creditorId(spec.creditorIndex());
            for (CreditorUserView u : rt.as(gestor, () -> portal.status(creditorId)).users()) {
                if ("INVITED".equals(u.status())) {
                    problems.add("convite pendente");
                }
                if (u.locked()) {
                    problems.add("pessoa bloqueada");
                }
            }
        }
        problems.stream().distinct().forEach(p -> rt.warn("portal do credor: " + p + " ao fim da carga"));
    }

    private CreditorUserView user(TenantSeed seed, Person person) {
        return rt.as(seed.actor("gestor"), () -> portal.status(person.creditorId)).users().stream()
                .filter(u -> u.email().equalsIgnoreCase(person.spec.email())).findFirst()
                .orElseThrow(() -> new IllegalStateException("pessoa do credor de demonstração não convidada"));
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(Math.max(0, duration.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("carga interrompida", e);
        }
    }

    /**
     * Ação de demonstração da pessoa do credor: recusa da API (situação e código, sem corpo) ou de regra vira aviso da
     * carga, sem derrubar as demais — como os cenários opcionais.
     */
    private boolean attempt(String scenario, Supplier<Boolean> action) {
        try {
            return action.get();
        } catch (CallFailedException e) {
            rt.warn("portal do credor: " + scenario + ": HTTP " + e.status() + (e.code() == null ? "" : " " + e.code()));
        } catch (DomainException e) {
            rt.warn("portal do credor: " + scenario + ": " + e.code());
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw e; // carga interrompida: não vira aviso
            }
            // falha técnica (convite que não chegou ao Mailpit, E/S com a própria API, resposta inesperada): aviso só
            // com o tipo, porque a mensagem pode citar URL ou corpo; as demais ações e etapas seguem
            rt.warn("portal do credor: " + scenario + ": " + e.getClass().getSimpleName());
        }
        return false;
    }
}
