package br.com.gestorcobranca.sandbox.application.service;

import br.com.gestorcobranca.publicapi.application.port.in.IntegrationTokenUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.IntegrationTokenUseCases.ScopeView;
import br.com.gestorcobranca.publicapi.application.port.in.IntegrationTokenUseCases.TokenView;
import br.com.gestorcobranca.publicapi.application.port.in.IssueIntegrationToken;
import br.com.gestorcobranca.publicapi.application.port.in.IssueIntegrationToken.IssueTokenCommand;
import br.com.gestorcobranca.publicapi.application.port.in.IssueIntegrationToken.IssuedToken;
import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import br.com.gestorcobranca.sandbox.application.port.out.DemoTokenVault;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.CreditorSpec;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Tokens de demonstração da API pública ({@code docs/tecnico/desenho/api-publica.md}): para cada credor de cada
 * assessoria, um token {@code sandbox-<prefixo>-completo} com todos os escopos do catálogo (leitura e escrita da
 * carteira, mais a posição do faturamento da API e do repasse: {@code billing:read}, {@code settlement:read}) e, no 1º
 * credor, um token {@code sandbox-<prefixo>-leitura} só com os escopos de leitura da carteira — sem escrita e sem os
 * escopos de posição, para demonstrar a recusa (403) de quem não recebeu esses escopos.
 * <p>
 * Emitidos pelo caso de uso público {@link IssueIntegrationToken}, como o <strong>administrador</strong> da assessoria —
 * a regra de emissão só deixa conceder escopos que o emissor tem (o perfil Integrações, por exemplo, não teria
 * {@code titles:*}). O segredo só existe no retorno da emissão: vai para o arquivo do volume exclusivo do sandbox
 * ({@link DemoTokenVault}, lido pelo {@code scripts/sandbox/tokens.sh}) e nunca para o log.
 * <p>
 * Idempotente pelo nome: token ativo existente não é emitido de novo. Se o arquivo se perdeu (volume removido à
 * parte), o segredo é reemitido pela <em>rotação</em> do próprio token, sem carência. Os escopos de um token não mudam
 * depois da emissão (regra da API pública): token já existente com escopos diferentes dos esperados (ex.: emitido antes
 * de {@code billing:read}/{@code settlement:read} existirem) é mantido como está e vira aviso da carga, que recomenda o
 * {@code reset.sh} (recria os tokens com os escopos atuais). Revogar e reemitir por conta própria trocaria o segredo
 * que a demonstração pode estar usando.
 */
@SandboxSeedComponent
class IntegrationTokensSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(IntegrationTokensSeeder.class);
    /** Escopos de posição (faturamento da API e repasse): só no token completo. */
    static final Set<String> POSITION_SCOPES = Set.of("billing:read", "settlement:read");

    private final IssueIntegrationToken issuer;
    private final IntegrationTokenUseCases tokens;
    private final DemoTokenVault vault;
    private final SeedRuntime rt;

    IntegrationTokensSeeder(IssueIntegrationToken issuer, IntegrationTokenUseCases tokens, DemoTokenVault vault,
                            SeedRuntime rt) {
        this.issuer = issuer;
        this.tokens = tokens;
        this.vault = vault;
        this.rt = rt;
    }

    void seed(TenantSeed seed) {
        Actor admin = seed.actor("admin");
        List<String> all = rt.as(admin, tokens::scopes).stream().map(ScopeView::code).sorted().toList();
        List<String> readOnly = all.stream().filter(s -> s.endsWith(":read") && !POSITION_SCOPES.contains(s)).toList();
        Set<UUID> stored = vault.storedTokenIds();
        for (int c = 0; c < seed.spec.creditors().size(); c++) {
            CreditorSpec creditor = seed.creditor(c);
            UUID creditorId = seed.creditorId(c);
            ensure(seed, admin, stored, creditor, creditorId, tokenName(creditor, "completo"),
                    "Token de demonstração com leitura e escrita da carteira e posição do credor (sandbox)", all);
            if (c == 0) {
                ensure(seed, admin, stored, creditor, creditorId, tokenName(creditor, "leitura"),
                        "Token de demonstração somente leitura da carteira (sandbox)", readOnly);
            }
        }
    }

    static String tokenName(CreditorSpec creditor, String kind) {
        return "sandbox-" + creditor.prefix().toLowerCase() + "-" + kind;
    }

    private void ensure(TenantSeed seed, Actor admin, Set<UUID> stored, CreditorSpec creditor, UUID creditorId,
                        String name, String description, List<String> scopes) {
        Optional<TokenView> existing = rt.as(admin, () -> tokens.search(creditorId, "ACTIVE", PageQuery.of(0, 100)))
                .content().stream().filter(t -> name.equals(t.name())).findFirst();
        IssuedToken issued;
        if (existing.isPresent()) {
            if (!existing.get().scopes().equals(Set.copyOf(scopes))) {
                rt.warn("token de demonstração " + name + " com escopos anteriores (faltam "
                        + scopes.stream().filter(s -> !existing.get().scopes().contains(s)).sorted().toList()
                        + "); rode ./scripts/sandbox/reset.sh para recriá-lo");
            }
            if (stored.contains(existing.get().id())) {
                return;
            }
            issued = rt.as(admin, () -> tokens.rotate(existing.get().id(), 0));
            LOG.atInfo().addKeyValue("tenantId", seed.tenantId).addKeyValue("tokenId", existing.get().id())
                    .log("Segredo do token de demonstração reemitido por rotação (arquivo do sandbox sem a entrada)");
        } else {
            issued = rt.as(admin, () -> issuer.issue(new IssueTokenCommand(creditorId, name, description,
                    Set.copyOf(scopes), null, List.of(), null)));
            LOG.atInfo().addKeyValue("tenantId", seed.tenantId).addKeyValue("tokenId", issued.details().id())
                    .addKeyValue("prefix", issued.details().prefix())
                    .log("Token de demonstração da API pública emitido");
        }
        vault.store(new DemoTokenVault.Entry(issued.details().id(), seed.spec.slug(), creditor.tradeName(), name,
                issued.details().scopes().stream().sorted().toList(), issued.token()));
    }
}
