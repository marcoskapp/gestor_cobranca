package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.in.SoftphoneUseCases;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.ProviderRejectedException;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.SoftphoneToken;
import br.com.gestorcobranca.telephony.domain.model.Agent;
import br.com.gestorcobranca.telephony.domain.model.ProviderType;
import br.com.gestorcobranca.telephony.domain.model.TelephonyAccount;
import br.com.gestorcobranca.telephony.domain.repository.AgentRepository;
import br.com.gestorcobranca.telephony.domain.repository.TelephonyAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Tokens do softphone de navegador do operador corrente (nunca de outro usuário). Para cada conta
 * ativa e utilizável neste ambiente, no escopo de credores do operador, cujo ramal do operador nela
 * é uma identidade de navegador do provedor, o adaptador emite um token curto restrito a essa
 * identidade. Log técnico só com ids e provedor; métrica {@code telephony.softphone.tokens}.
 */
@Service
@Transactional(readOnly = true)
class SoftphoneService implements SoftphoneUseCases {

    private static final Logger LOG = LoggerFactory.getLogger(SoftphoneService.class);

    private final AgentRepository agents;
    private final TelephonyAccountRepository accounts;
    private final AgentEndpoints endpoints;
    private final ProviderResolver resolver;
    private final CurrentActor currentActor;
    private final AccessScope accessScope;
    private final TelephonyMetrics metrics;
    private final TelephonyProperties properties;

    SoftphoneService(AgentRepository agents, TelephonyAccountRepository accounts, AgentEndpoints endpoints,
                     ProviderResolver resolver, CurrentActor currentActor, AccessScope accessScope,
                     TelephonyMetrics metrics, TelephonyProperties properties) {
        this.agents = agents;
        this.accounts = accounts;
        this.endpoints = endpoints;
        this.resolver = resolver;
        this.currentActor = currentActor;
        this.accessScope = accessScope;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Override
    public List<SoftphoneSession> sessions() {
        Agent agent = agents.findByUserId(currentActor.actorId()).filter(Agent::isActive)
                .orElseThrow(() -> new ConflictException("telephony.agent_not_configured",
                        "Operador sem ramal habilitado na telefonia"));
        List<SoftphoneSession> sessions = new ArrayList<>();
        for (TelephonyAccount account : accounts.findAllByOrderByNameAsc()) {
            if (!resolver.usable(account) || (account.getCreditorId() != null
                    && !accessScope.canAccessCreditor(account.getCreditorId()))) {
                continue;
            }
            Optional<TelephonyProvider> provider = resolver.provider(account.getProvider());
            Optional<String> identity = provider.flatMap(p -> endpoints.extensionFor(agent, account.getId())
                    .flatMap(p::softphoneIdentity));
            if (identity.isEmpty()) {
                continue;
            }
            String name = account.getProvider().name().toLowerCase(Locale.ROOT);
            try {
                SoftphoneToken token = provider.get().issueSoftphoneToken(resolver.context(account), identity.get(),
                        ttl(account.getProvider()));
                sessions.add(new SoftphoneSession(account.getId(), account.getProvider().name(), token.identity(),
                        token.token(), token.expiresAt(), token.options()));
                metrics.softphoneToken(name, "issued");
                LOG.atInfo().addKeyValue("userId", agent.getUserId()).addKeyValue("accountId", account.getId())
                        .addKeyValue("provider", account.getProvider()).log("Token do softphone emitido");
            } catch (ProviderRejectedException | UnsupportedOperationException e) {
                metrics.softphoneToken(name, "failed");
                LOG.atWarn().addKeyValue("userId", agent.getUserId()).addKeyValue("accountId", account.getId())
                        .addKeyValue("provider", account.getProvider()).log("Token do softphone recusado");
            }
        }
        return sessions;
    }

    private Duration ttl(ProviderType provider) {
        return provider == ProviderType.TWILIO ? properties.twilio().softphoneTokenTtl() : Duration.ofHours(1);
    }
}
