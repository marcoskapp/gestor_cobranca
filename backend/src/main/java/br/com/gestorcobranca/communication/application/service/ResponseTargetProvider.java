package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.ResponseTarget;
import br.com.gestorcobranca.communication.domain.repository.ResponseTargetRepository;
import br.com.gestorcobranca.communication.domain.service.ResponseTargets;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/** Metas vigentes da assessoria corrente: as gravadas ou, por canal sem registro, os padrões. */
@Component
class ResponseTargetProvider {

    private final ResponseTargetRepository targets;

    ResponseTargetProvider(ResponseTargetRepository targets) {
        this.targets = targets;
    }

    ResponseTargets current() {
        Map<ChannelType, ResponseTargets.Target> configured = new EnumMap<>(ChannelType.class);
        for (ResponseTarget t : targets.findAll()) {
            configured.put(t.getChannel(), new ResponseTargets.Target(Duration.ofMinutes(t.getWarningMinutes()),
                    Duration.ofMinutes(t.getBreachMinutes())));
        }
        return ResponseTargets.of(configured);
    }
}
