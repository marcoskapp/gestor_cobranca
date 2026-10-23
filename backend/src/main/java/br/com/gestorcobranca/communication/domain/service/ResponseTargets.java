package br.com.gestorcobranca.communication.domain.service;

import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.SlaStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Metas de primeira resposta vigentes por canal (puro). Canais instantâneos esperam resposta em
 * minutos; e-mail, no mesmo dia. A espera conta em minutos corridos a partir da primeira mensagem
 * do devedor sem resposta ({@code awaitingSince}).
 */
public final class ResponseTargets {

    /** Meta de um canal. */
    public record Target(Duration warning, Duration breach) {
    }

    /** Padrões da plataforma: WhatsApp e SMS 15/60 min; e-mail 4/24 h. */
    public static final ResponseTargets DEFAULTS = new ResponseTargets(Map.of(
            ChannelType.WHATSAPP, new Target(Duration.ofMinutes(15), Duration.ofMinutes(60)),
            ChannelType.SMS, new Target(Duration.ofMinutes(15), Duration.ofMinutes(60)),
            ChannelType.EMAIL, new Target(Duration.ofHours(4), Duration.ofHours(24))));

    private final Map<ChannelType, Target> targets;

    private ResponseTargets(Map<ChannelType, Target> targets) {
        this.targets = new EnumMap<>(targets);
    }

    /** Padrões substituídos pelos canais configurados. */
    public static ResponseTargets of(Map<ChannelType, Target> configured) {
        Map<ChannelType, Target> merged = new EnumMap<>(DEFAULTS.targets);
        merged.putAll(configured);
        return new ResponseTargets(merged);
    }

    public Target target(ChannelType channel) {
        return targets.get(channel);
    }

    /** Situação da espera; vazio se a conversa não aguarda resposta. */
    public Optional<SlaStatus> status(ChannelType channel, Instant awaitingSince, Instant now) {
        if (awaitingSince == null) {
            return Optional.empty();
        }
        Target t = target(channel);
        Duration waited = Duration.between(awaitingSince, now);
        if (waited.compareTo(t.breach()) >= 0) {
            return Optional.of(SlaStatus.BREACHED);
        }
        return Optional.of(waited.compareTo(t.warning()) >= 0 ? SlaStatus.WARNING : SlaStatus.OK);
    }

    /** Classificação da espera encerrada por uma primeira resposta. */
    public SlaStatus classify(ChannelType channel, Duration waited) {
        Target t = target(channel);
        return waited.compareTo(t.breach()) >= 0 ? SlaStatus.BREACHED
                : waited.compareTo(t.warning()) >= 0 ? SlaStatus.WARNING : SlaStatus.OK;
    }
}
