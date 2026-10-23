package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.communication.application.port.in.ResponseTargetUseCases;
import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.ResponseTarget;
import br.com.gestorcobranca.communication.domain.repository.ResponseTargetRepository;
import br.com.gestorcobranca.communication.domain.service.ResponseTargets;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
class ResponseTargetService implements ResponseTargetUseCases {

    static final String ENTITY = "response_target";

    private final ResponseTargetRepository targets;
    private final ResponseTargetProvider provider;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;

    ResponseTargetService(ResponseTargetRepository targets, ResponseTargetProvider provider, AccessScope accessScope,
                          AuditTrail auditTrail) {
        this.targets = targets;
        this.provider = provider;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResponseTargetView> list() {
        accessScope.requirePermission(Permission.INBOX_VIEW);
        Map<ChannelType, ResponseTarget> stored = targets.findAll().stream()
                .collect(Collectors.toMap(ResponseTarget::getChannel, Function.identity()));
        ResponseTargets current = provider.current();
        return Arrays.stream(ChannelType.values()).map(ch -> view(ch, current, Optional.ofNullable(stored.get(ch))))
                .toList();
    }

    @Override
    public ResponseTargetView update(String channel, int warningMinutes, int breachMinutes) {
        accessScope.requirePermission(Permission.INBOX_MANAGE);
        accessScope.requireAllCreditors(ENTITY);
        ChannelType type = CommunicationEnums.parse(ChannelType.class, channel, "response_target.channel_invalid", "Canal");
        ResponseTargets.Target before = provider.current().target(type);
        ResponseTarget target = targets.findByChannel(type).orElse(null);
        if (target == null) {
            target = new ResponseTarget(type, warningMinutes, breachMinutes);
        } else {
            target.define(warningMinutes, breachMinutes);
        }
        ResponseTarget saved = targets.save(target);
        auditTrail.record(AuditEntry.of("response_target.updated", ENTITY, saved.getId())
                .meta("channel", type.name())
                .change("warningMinutes", before.warning().toMinutes(), warningMinutes)
                .change("breachMinutes", before.breach().toMinutes(), breachMinutes));
        return new ResponseTargetView(type.name(), warningMinutes, breachMinutes, true, saved.getUpdatedAt());
    }

    private static ResponseTargetView view(ChannelType channel, ResponseTargets current, Optional<ResponseTarget> stored) {
        ResponseTargets.Target t = current.target(channel);
        return new ResponseTargetView(channel.name(), (int) t.warning().toMinutes(), (int) t.breach().toMinutes(),
                stored.isPresent(), stored.map(ResponseTarget::getUpdatedAt).orElse(null));
    }
}
