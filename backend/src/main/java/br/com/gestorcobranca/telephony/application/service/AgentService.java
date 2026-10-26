package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.telephony.application.port.in.AgentUseCases;
import br.com.gestorcobranca.telephony.domain.model.Agent;
import br.com.gestorcobranca.telephony.domain.model.AgentEndpoint;
import br.com.gestorcobranca.telephony.domain.model.TelephonyAccount;
import br.com.gestorcobranca.telephony.domain.repository.AgentEndpointRepository;
import br.com.gestorcobranca.telephony.domain.repository.TelephonyAccountRepository;
import br.com.gestorcobranca.telephony.domain.model.DialerCampaign;
import br.com.gestorcobranca.telephony.domain.model.PauseReason;
import br.com.gestorcobranca.telephony.domain.repository.AgentRepository;
import br.com.gestorcobranca.telephony.domain.repository.DialerCampaignRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Operadores e presença. Entrar numa campanha exige a campanha ativa (ou pausada), o credor dela
 * no escopo do operador — o discador só distribui chamadas de casos que ele pode atender — e, se a
 * campanha tem conta própria no roteamento, que o operador seja alcançável por ela.
 */
@Service
@Transactional
class AgentService implements AgentUseCases {

    private final AgentRepository agents;
    private final AgentEndpointRepository endpointRepository;
    private final AgentEndpoints endpoints;
    private final TelephonyAccountRepository accounts;
    private final AccountRouter router;
    private final DialerCampaignRepository campaigns;
    private final PresenceRecorder presence;
    private final DialerTicks ticks;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final Clock clock;

    AgentService(AgentRepository agents, AgentEndpointRepository endpointRepository, AgentEndpoints endpoints,
                 TelephonyAccountRepository accounts, AccountRouter router, DialerCampaignRepository campaigns,
                 PresenceRecorder presence, DialerTicks ticks, AccessScope accessScope, CurrentActor currentActor,
                 AuditTrail auditTrail, Clock clock) {
        this.agents = agents;
        this.endpointRepository = endpointRepository;
        this.endpoints = endpoints;
        this.accounts = accounts;
        this.router = router;
        this.campaigns = campaigns;
        this.presence = presence;
        this.ticks = ticks;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    @Override
    public AgentView configure(UUID userId, ConfigureAgentCommand command) {
        Guard.require(userId != null, "agent.user_required", "Usuário obrigatório");
        Guard.require(command != null, "agent.required", "Dados do operador obrigatórios");
        Instant now = Instant.now(clock);
        Agent agent = agents.findByUserIdForUpdate(userId).orElse(null);
        if (agent == null) {
            agent = agents.saveAndFlush(new Agent(userId, command.extension(), now));
            if (!command.active()) {
                agent.deactivate(now);
            }
            auditTrail.record(AuditEntry.of("telephony_agent.configured", "telephony_agent", agent.getId())
                    .created("userId", userId).created("extension", agent.getExtension())
                    .created("active", agent.isActive()));
            return CallViews.agent(agents.save(agent));
        }
        String beforeExtension = agent.getExtension();
        boolean beforeActive = agent.isActive();
        agent.changeExtension(command.extension());
        if (command.active() && !agent.isActive()) {
            agent.activate();
        } else if (!command.active() && agent.isActive()) {
            presence.record(agent.deactivate(now), "configuration");
        }
        agents.save(agent);
        auditTrail.record(AuditEntry.of("telephony_agent.updated", "telephony_agent", agent.getId())
                .change("extension", beforeExtension, agent.getExtension())
                .change("active", beforeActive, agent.isActive()).meta("userId", userId));
        return CallViews.agent(agent);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentView> list() {
        return agents.findAllByOrderByStatusAscStatusSinceAsc().stream().map(CallViews::agent).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AgentView me() {
        return CallViews.agent(agents.findByUserId(currentActor.actorId()).orElseThrow(this::notConfigured));
    }

    @Override
    public AgentView login(UUID campaignId) {
        Agent agent = mine();
        if (campaignId != null) {
            requireJoinable(agent, campaignId);
        }
        presence.record(agent.login(campaignId, Instant.now(clock)), "operator");
        return CallViews.agent(agents.save(agent));
    }

    @Override
    public AgentView available() {
        Agent agent = mine();
        presence.record(agent.available(Instant.now(clock)), "operator");
        return CallViews.agent(agents.save(agent));
    }

    @Override
    public AgentView pause(String reason) {
        Agent agent = mine();
        PauseReason why = TelephonyEnums.parse(PauseReason.class, reason, "agent.pause_reason_invalid", "Motivo da pausa");
        presence.record(agent.pause(why, Instant.now(clock)), "operator");
        return CallViews.agent(agents.save(agent));
    }

    @Override
    public AgentView joinCampaign(UUID campaignId) {
        Agent agent = mine();
        if (campaignId != null) {
            requireJoinable(agent, campaignId);
        }
        agent.joinCampaign(campaignId);
        agents.save(agent);
        if (campaignId != null && agent.getStatus() == br.com.gestorcobranca.telephony.domain.model.PresenceStatus.AVAILABLE) {
            ticks.requestNow(campaignId);
        }
        return CallViews.agent(agent);
    }

    @Override
    public AgentView logout() {
        Agent agent = mine();
        presence.record(agent.logout(false, Instant.now(clock)), "operator");
        return CallViews.agent(agents.save(agent));
    }

    @Override
    public void heartbeat() {
        Agent agent = mine();
        agent.heartbeat(Instant.now(clock));
        agents.save(agent);
    }

    @Override
    public AgentView forceLogout(UUID userId, String reason) {
        Guard.require(reason != null && !reason.isBlank() && reason.length() <= 300, "agent.reason_required",
                "Motivo obrigatório (até 300 caracteres)");
        Agent agent = agents.findByUserIdForUpdate(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("telephony_agent", userId));
        String before = agent.getStatus().name();
        presence.record(agent.logout(true, Instant.now(clock)), "supervisor");
        agents.save(agent);
        auditTrail.record(AuditEntry.of("telephony_agent.forced_logout", "telephony_agent", agent.getId())
                .change("status", before, agent.getStatus().name()).meta("userId", userId).reason(reason.trim()));
        return CallViews.agent(agent);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EndpointView> endpoints() {
        List<Agent> all = agents.findAllByOrderByStatusAscStatusSinceAsc();
        Map<UUID, Agent> byId = all.stream().collect(Collectors.toMap(Agent::getId, Function.identity()));
        return endpointRepository.findAllByAgentIdIn(byId.keySet()).stream()
                .map(e -> view(e, byId.get(e.getAgentId()))).toList();
    }

    @Override
    public EndpointView configureEndpoint(UUID userId, UUID accountId, ConfigureEndpointCommand command) {
        Guard.require(command != null, "agent_endpoint.required", "Dados do ramal obrigatórios");
        Agent agent = agents.findByUserIdForUpdate(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("telephony_agent", userId));
        TelephonyAccount account = accounts.findById(accountId)
                .orElseThrow(() -> ResourceNotFoundException.of("telephony_account", accountId));
        if (account.getCreditorId() != null) {
            accessScope.requireVisible("telephony_account", account.getId(), account.getCreditorId());
        }
        AgentEndpoint endpoint = endpointRepository.findByAgentIdAndAccountId(agent.getId(), accountId).orElse(null);
        if (endpoint == null) {
            endpoint = endpointRepository.save(new AgentEndpoint(agent.getId(), account.getId(), command.extension(),
                    command.active()));
            auditTrail.record(AuditEntry.of("telephony_agent.endpoint_configured", "telephony_agent", agent.getId())
                    .created("accountId", accountId).created("provider", account.getProvider())
                    .created("extension", endpoint.getExtension()).created("active", endpoint.isActive())
                    .meta("userId", userId));
            return view(endpoint, agent);
        }
        String beforeExtension = endpoint.getExtension();
        boolean beforeActive = endpoint.isActive();
        endpoint.change(command.extension(), command.active());
        endpointRepository.save(endpoint);
        auditTrail.record(AuditEntry.of("telephony_agent.endpoint_updated", "telephony_agent", agent.getId())
                .change("extension", beforeExtension, endpoint.getExtension())
                .change("active", beforeActive, endpoint.isActive())
                .meta("accountId", accountId).meta("userId", userId));
        return view(endpoint, agent);
    }

    @Override
    public void removeEndpoint(UUID userId, UUID accountId) {
        Agent agent = agents.findByUserIdForUpdate(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("telephony_agent", userId));
        endpointRepository.findByAgentIdAndAccountId(agent.getId(), accountId).ifPresent(e -> {
            endpointRepository.delete(e);
            auditTrail.record(AuditEntry.of("telephony_agent.endpoint_removed", "telephony_agent", agent.getId())
                    .change("extension", e.getExtension(), null).change("active", e.isActive(), null)
                    .meta("accountId", accountId).meta("userId", userId));
        });
    }

    private static EndpointView view(AgentEndpoint e, Agent agent) {
        return new EndpointView(e.getId(), e.getAgentId(), agent == null ? null : agent.getUserId(), e.getAccountId(),
                e.getExtension(), e.isActive(), e.getVersion() == null ? 0 : e.getVersion());
    }

    private Agent mine() {
        return agents.findByUserIdForUpdate(currentActor.actorId()).orElseThrow(this::notConfigured);
    }

    private ConflictException notConfigured() {
        return new ConflictException("telephony.agent_not_configured", "Operador sem ramal habilitado na telefonia");
    }

    private void requireJoinable(Agent agent, UUID campaignId) {
        DialerCampaign campaign = campaigns.findById(campaignId)
                .orElseThrow(() -> ResourceNotFoundException.of("dialer_campaign", campaignId));
        if (campaign.getCreditorId() == null) {
            accessScope.requireAllCreditors("dialer_campaign");
        } else {
            accessScope.requireVisible("dialer_campaign", campaign.getId(), campaign.getCreditorId());
        }
        Guard.requireState(campaign.getStatus() == br.com.gestorcobranca.telephony.domain.model.DialerCampaignStatus.ACTIVE
                        || campaign.getStatus() == br.com.gestorcobranca.telephony.domain.model.DialerCampaignStatus.PAUSED,
                "dialer_campaign.not_active", "Campanha não está ativa");
        router.campaignAccount(campaignId).ifPresent(account -> {
            if (endpoints.extensionFor(agent, account.getId()).isEmpty()) {
                throw new ConflictException("telephony.agent_not_on_account",
                        "O operador não tem ramal na conta de telefonia desta campanha");
            }
        });
    }
}
