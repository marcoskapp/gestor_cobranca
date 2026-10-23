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
import br.com.gestorcobranca.telephony.domain.model.DialerCampaign;
import br.com.gestorcobranca.telephony.domain.model.PauseReason;
import br.com.gestorcobranca.telephony.domain.repository.AgentRepository;
import br.com.gestorcobranca.telephony.domain.repository.DialerCampaignRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Operadores e presença. Entrar numa campanha exige a campanha ativa (ou pausada) e o credor dela
 * no escopo do operador — o discador só distribui chamadas de casos que ele pode atender.
 */
@Service
@Transactional
class AgentService implements AgentUseCases {

    private final AgentRepository agents;
    private final DialerCampaignRepository campaigns;
    private final PresenceRecorder presence;
    private final DialerTicks ticks;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final Clock clock;

    AgentService(AgentRepository agents, DialerCampaignRepository campaigns, PresenceRecorder presence,
                 DialerTicks ticks, AccessScope accessScope, CurrentActor currentActor, AuditTrail auditTrail, Clock clock) {
        this.agents = agents;
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
            requireJoinable(campaignId);
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
            requireJoinable(campaignId);
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

    private Agent mine() {
        return agents.findByUserIdForUpdate(currentActor.actorId()).orElseThrow(this::notConfigured);
    }

    private ConflictException notConfigured() {
        return new ConflictException("telephony.agent_not_configured", "Operador sem ramal habilitado na telefonia");
    }

    private void requireJoinable(UUID campaignId) {
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
    }
}
