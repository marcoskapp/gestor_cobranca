package br.com.gestorcobranca.sandbox.application.service;

import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.TenantSpec;
import br.com.gestorcobranca.shared.config.TimeConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.time.Duration;
import java.time.LocalDate;
import java.util.function.Consumer;

/**
 * Orquestra a carga de dados fictícios do sandbox, numa thread própria iniciada quando a aplicação fica no ar (os jobs
 * e consumidores de eventos já estão rodando: a carga depende deles, como a operação real). A prontidão do sandbox
 * ({@code /actuator/health/readiness}) só fica UP quando a carga termina.
 * <p>
 * Ordem: administrador da plataforma → para cada assessoria: provisionamento → administrador → credores, contratos e
 * carteiras → usuários de todos os perfis → configuração operacional → canais e provedores simulados → tokens de
 * integração (ponto de extensão) → carga das carteiras → cenários operacionais e jurídicos → réguas → distribuição,
 * demonstrativo e painéis. Cada etapa confere o que já existe: subir de novo não duplica nada.
 * <p>
 * Ativação: perfil {@code sandbox} e {@code app.sandbox.seed.enabled=true} ({@code SandboxSeedGuard} recusa outras
 * combinações).
 */
@SandboxSeedComponent
class SandboxSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(SandboxSeeder.class);

    private final IdentitySeeder identity;
    private final OrganizationSeeder organization;
    private final ConfigurationSeeder configuration;
    private final ChannelSeeder channels;
    private final IntegrationTokensSeeder integrationTokens;
    private final PortfolioLoadSeeder portfolioLoad;
    private final OperationSeeder operations;
    private final LegalSeeder legal;
    private final CampaignSeeder campaigns;
    private final SeedStatus status;
    private final SeedRuntime rt;
    private final MeterRegistry meters;

    SandboxSeeder(IdentitySeeder identity, OrganizationSeeder organization, ConfigurationSeeder configuration,
                  ChannelSeeder channels, IntegrationTokensSeeder integrationTokens, PortfolioLoadSeeder portfolioLoad,
                  OperationSeeder operations, LegalSeeder legal, CampaignSeeder campaigns, SeedStatus status,
                  SeedRuntime rt, MeterRegistry meters) {
        this.identity = identity;
        this.organization = organization;
        this.configuration = configuration;
        this.channels = channels;
        this.integrationTokens = integrationTokens;
        this.portfolioLoad = portfolioLoad;
        this.operations = operations;
        this.legal = legal;
        this.campaigns = campaigns;
        this.status = status;
        this.rt = rt;
        this.meters = meters;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        Thread.ofPlatform().name("sandbox-seed").daemon(true).start(this::run);
    }

    void run() {
        long started = System.nanoTime();
        status.started(rt.now());
        LOG.warn("Carga de dados FICTÍCIOS do sandbox iniciada (perfil sandbox; nunca usar em produção)");
        try {
            step("administrador da plataforma", null, t -> identity.activatePlatformAdmin());
            LocalDate today = LocalDate.now(rt.clock().withZone(TimeConfig.BUSINESS_ZONE));
            for (TenantSpec spec : DemoCatalog.tenants()) {
                TenantSeed seed = new TenantSeed(spec, today);
                step("provisionamento", seed, identity::provisionTenant);
                step("administrador da assessoria", seed, identity::prepareAdmin);
                step("credores, contratos e carteiras", seed, organization::seed);
                step("usuários de demonstração", seed, identity::seedUsers);
                step("configuração operacional", seed, configuration::seed);
                step("metas dos negociadores", seed, configuration::goals);
                step("canais e provedores simulados", seed, channels::seed);
                step("tokens de integração", seed, integrationTokens::seed);
                step("carga das carteiras", seed, portfolioLoad::seed);
                step("cenários operacionais", seed, operations::seed);
                step("fluxo jurídico", seed, legal::seed);
                step("réguas de cobrança", seed, campaigns::seed);
                step("distribuição, prestação de contas e painéis", seed, operations::finish);
            }
            status.completed(rt.now());
            record("completed", started);
            LOG.atInfo().addKeyValue("durationMs", Duration.ofNanos(System.nanoTime() - started).toMillis())
                    .addKeyValue("warnings", status.warnings().size())
                    .log("Carga do sandbox concluída");
        } catch (RuntimeException e) {
            status.failed(rt.now(), e.getClass().getSimpleName() + ": " + e.getMessage());
            record("failed", started);
            LOG.error("Carga do sandbox falhou na etapa '{}'", status.phase(), e);
        }
    }

    private void step(String name, TenantSeed seed, Consumer<TenantSeed> action) {
        String phase = seed == null ? name : seed.spec.slug() + ": " + name;
        status.phase(phase);
        long started = System.nanoTime();
        action.accept(seed);
        long millis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        Timer.builder("sandbox.seed.step").tag("step", name).register(meters)
                .record(Duration.ofMillis(millis));
        LOG.atInfo().addKeyValue("step", name).addKeyValue("tenant", seed == null ? "plataforma" : seed.spec.slug())
                .addKeyValue("durationMs", millis).log("Etapa da carga do sandbox concluída");
    }

    private void record(String outcome, long startedNanos) {
        Timer.builder("sandbox.seed.duration").tag("outcome", outcome).register(meters)
                .record(Duration.ofNanos(System.nanoTime() - startedNanos));
    }
}
