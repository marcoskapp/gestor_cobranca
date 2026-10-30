package br.com.gestorcobranca.sandbox.application.service;

import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import br.com.gestorcobranca.assistant.application.port.in.AssistantPolicyUseCases;
import br.com.gestorcobranca.assistant.application.port.in.AssistantPolicyUseCases.CreatePolicyCommand;
import br.com.gestorcobranca.communication.application.port.in.ChannelAccountUseCases;
import br.com.gestorcobranca.communication.application.port.in.ChannelAccountUseCases.AccountView;
import br.com.gestorcobranca.communication.application.port.in.ChannelAccountUseCases.CreateAccountCommand;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.ApprovalCommand;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.CreateTemplateCommand;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.TemplateContent;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.TemplateFilter;
import br.com.gestorcobranca.communication.application.port.in.TemplateUseCases.TemplateView;
import br.com.gestorcobranca.payment.application.port.in.PixAccountUseCases;
import br.com.gestorcobranca.payment.application.port.in.PixAccountUseCases.CredentialsCommand;
import br.com.gestorcobranca.portal.application.port.in.PortalConfigurationUseCases;
import br.com.gestorcobranca.portal.application.port.in.PortalConfigurationUseCases.ConfigurationCommand;
import br.com.gestorcobranca.sandbox.application.service.catalog.TemplateCatalog;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.CreditorSpec;
import br.com.gestorcobranca.sandbox.domain.service.FictitiousData;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.telephony.application.port.in.AgentUseCases;
import br.com.gestorcobranca.telephony.application.port.in.AgentUseCases.ConfigureAgentCommand;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyAccountUseCases;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Canais e provedores, todos em modo <strong>simulado</strong> (nada sai para fora do ambiente): WhatsApp e SMS pelo
 * simulador do communication, e-mail pelo SMTP da plataforma (Mailpit no sandbox), Pix pelo PSP simulado e telefonia
 * pelo simulador. Também templates de mensagem, portal de autonegociação e assistente de IA (modelo simulado).
 * Segredos de webhook são aleatórios por subida e ficam cifrados nas contas (nunca em configuração).
 */
@SandboxSeedComponent
class ChannelSeeder {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ChannelAccountUseCases channels;
    private final TemplateUseCases templates;
    private final PixAccountUseCases pixAccounts;
    private final TelephonyAccountUseCases telephonyAccounts;
    private final AgentUseCases agents;
    private final PortalConfigurationUseCases portals;
    private final AssistantPolicyUseCases assistantPolicies;
    private final SeedRuntime rt;

    ChannelSeeder(ChannelAccountUseCases channels, TemplateUseCases templates, PixAccountUseCases pixAccounts,
                  TelephonyAccountUseCases telephonyAccounts, AgentUseCases agents, PortalConfigurationUseCases portals,
                  AssistantPolicyUseCases assistantPolicies, SeedRuntime rt) {
        this.channels = channels;
        this.templates = templates;
        this.pixAccounts = pixAccounts;
        this.telephonyAccounts = telephonyAccounts;
        this.agents = agents;
        this.portals = portals;
        this.assistantPolicies = assistantPolicies;
        this.rt = rt;
    }

    void seed(TenantSeed seed) {
        channelAccounts(seed);
        templates(seed);
        pix(seed);
        telephony(seed);
        portal(seed);
        assistant(seed);
    }

    private void channelAccounts(TenantSeed seed) {
        Actor integracao = seed.actor("integracao");
        List<AccountView> existing = rt.as(integracao, channels::list);
        seed.whatsappAccountId = account(existing, "WHATSAPP").orElseGet(() -> rt.as(integracao, () -> channels.create(
                new CreateAccountCommand("WHATSAPP", "SANDBOX", null, "WhatsApp (simulador)", "+5511900000000",
                        seed.spec.tradeName(), null, Map.of()))).id());
        if (account(existing, "SMS").isEmpty()) {
            rt.as(integracao, () -> channels.create(new CreateAccountCommand("SMS", "SANDBOX", null, "SMS (simulador)",
                    "29000", seed.spec.tradeName(), null, Map.of())));
        }
        if (account(existing, "EMAIL").isEmpty()) {
            // SMTP da plataforma: no sandbox é o Mailpit — os e-mails aos devedores fictícios ficam visíveis lá
            rt.as(integracao, () -> channels.create(new CreateAccountCommand("EMAIL", "SMTP", null,
                    "E-mail da assessoria", seed.spec.contactEmail("cobranca"), seed.spec.tradeName(),
                    seed.spec.contactEmail("atendimento"), Map.of())));
        }
    }

    private static Optional<UUID> account(List<AccountView> accounts, String channel) {
        return accounts.stream().filter(a -> a.active() && channel.equals(a.channel()) && a.creditorId() == null)
                .map(AccountView::id).findFirst();
    }

    private void templates(TenantSeed seed) {
        Actor estrategia = seed.actor("estrategia");
        for (TemplateCatalog.Template template : TemplateCatalog.all()) {
            TemplateCatalog.Template t = template.withAgency(seed.spec.tradeName());
            List<TemplateView> existing = rt.as(estrategia, () -> templates.search(
                    new TemplateFilter(null, t.code(), t.channel(), null), PageQuery.of(0, 20))).content();
            if (existing.stream().anyMatch(v -> "PUBLISHED".equals(v.status()))) {
                continue;
            }
            TemplateView draft = existing.stream().filter(v -> "DRAFT".equals(v.status())).findFirst()
                    .orElseGet(() -> rt.as(estrategia, () -> templates.createDraft(new CreateTemplateCommand(null,
                            t.code(), t.channel(), new TemplateContent(t.name(), t.purpose(), t.subject(), t.body(),
                            t.variables(), null, null, null)))));
            if ("WHATSAPP".equals(t.channel()) && !"APPROVED".equals(draft.approvalStatus())) {
                // aprovação da Meta simulada: no sandbox não há envio real ao provedor
                rt.as(estrategia, () -> templates.registerApproval(draft.id(), new ApprovalCommand("APPROVED",
                        t.code().toLowerCase(), "sandbox-" + t.code().toLowerCase(),
                        "Aprovação simulada (ambiente de demonstração)", null)));
            }
            rt.as(estrategia, () -> templates.publish(draft.id()));
        }
    }

    private void pix(TenantSeed seed) {
        Actor integracao = seed.actor("integracao");
        if (rt.as(integracao, pixAccounts::list).isEmpty()) {
            rt.as(integracao, () -> pixAccounts.create(new PixAccountUseCases.CreateAccountCommand(null,
                    "PSP simulado (sandbox)", "SANDBOX", seed.spec.contactEmail("pix"), "https://psp.sandbox.local/api/v2",
                    "https://psp.sandbox.local/oauth/token", "HMAC", List.of(),
                    new CredentialsCommand("sandbox-client", secret(), secret(), null, null))));
        }
    }

    private void telephony(TenantSeed seed) {
        Actor gestor = seed.actor("gestor");
        if (rt.as(gestor, telephonyAccounts::list).isEmpty()) {
            rt.as(gestor, () -> telephonyAccounts.create(new TelephonyAccountUseCases.CreateAccountCommand("SANDBOX", null,
                    "Telefonia (simulador)", "+551130000000", true, true, Map.of("webhookSecret", secret()),
                    new java.math.BigDecimal("0.0900"))));
        }
        Set<UUID> configured = new java.util.HashSet<>();
        rt.as(gestor, agents::list).forEach(a -> configured.add(a.userId()));
        int extension = 1001;
        for (String key : List.of("operador", "operador2", "supervisor")) {
            UUID userId = seed.user(key);
            String ext = String.valueOf(extension++);
            if (!configured.contains(userId)) {
                rt.as(gestor, () -> agents.configure(userId, new ConfigureAgentCommand(ext, true)));
            }
        }
    }

    private void portal(TenantSeed seed) {
        Actor gestor = seed.actor("gestor");
        UUID creditorId = seed.creditorId(0);
        if (rt.as(gestor, portals::list).stream().anyMatch(c -> creditorId.equals(c.creditorId()))) {
            return;
        }
        CreditorSpec creditor = seed.creditor(0);
        String slug = seed.spec.slug() + "-" + FictitiousData.slug(creditor.tradeName());
        rt.as(gestor, () -> portals.create(new ConfigurationCommand(creditorId, slug, true, creditor.tradeName(),
                "#1D4E89", "#0B6E4F", Set.of(seed.portfolioId(0, 0), seed.portfolioId(0, 2)), Set.of("WHATSAPP", "EMAIL"),
                "BIRTH_DATE", 48,
                "Ambiente de demonstração com dados fictícios. Seus dados são tratados pela " + seed.spec.tradeName()
                        + " em nome de " + creditor.tradeName() + " para a cobrança e a negociação da dívida, conforme a "
                        + "LGPD. Você pode pedir acesso, correção ou informações pelo atendimento.",
                "Atendimento de segunda a sexta, das 8h às 20h, e aos sábados, das 8h às 14h (sandbox).")));
    }

    private void assistant(TenantSeed seed) {
        Actor gestor = seed.actor("gestor");
        String code = "assistente-" + seed.creditor(0).prefix().toLowerCase();
        if (rt.as(gestor, assistantPolicies::list).stream().anyMatch(p -> code.equals(p.code()))) {
            return;
        }
        // copiloto: o assistente sugere respostas ao negociador; modelo simulado (nenhum dado sai do ambiente)
        UUID policyId = rt.as(gestor, () -> assistantPolicies.createDraft(new CreatePolicyCommand(seed.creditorId(0), null,
                code, "Assistente — " + seed.creditor(0).tradeName(), assistantPolicies.template("COPILOT")))).id();
        rt.as(gestor, () -> assistantPolicies.publish(policyId));
    }

    private static String secret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
