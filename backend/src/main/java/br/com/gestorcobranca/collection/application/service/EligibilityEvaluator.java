package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.model.Eligibility;
import br.com.gestorcobranca.collection.domain.model.InteractionChannel;
import br.com.gestorcobranca.collection.domain.repository.DisputeRepository;
import br.com.gestorcobranca.collection.domain.service.EligibilityPolicy;
import br.com.gestorcobranca.collection.domain.service.EligibilityPolicy.ChannelAvailability;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.ContactDecision;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.ContactRequest;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.DenialReason;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reúne os fatos da elegibilidade (RB02) nos módulos de origem e delega a decisão a
 * {@link EligibilityPolicy}:
 * <ul>
 *   <li>carteira ativa e na vigência — {@code organization};</li>
 *   <li>títulos em aberto e bloqueio por contestação — posição do caso (cache do {@code debt}) e
 *       contestações do próprio módulo;</li>
 *   <li>devedor anonimizado — {@code debt};</li>
 *   <li>restrições de contato por canal — porteiro do {@code compliance} em modo consulta
 *       ({@code ContactGate#evaluate}, sem registrar tentativa).</li>
 * </ul>
 * Um canal é considerado <b>restrito</b> quando a negação decorre de restrição (devedor, canal,
 * anonimização) ou não tem fim previsto (ex.: política sem dias permitidos); negações
 * temporárias (fora da janela, feriado, limites) não tornam o caso inelegível — são verificadas
 * a cada contato. A carteira é consultada uma vez por avaliação em lote ({@link Batch}).
 */
@Component
class EligibilityEvaluator {

    private static final Set<DenialReason> RESTRICTIONS = EnumSet.of(DenialReason.RESTRICTED_DEBTOR,
            DenialReason.RESTRICTED_CHANNEL, DenialReason.DEBTOR_ANONYMIZED);
    private static final String ANONYMIZED = "ANONYMIZED";

    private final PortfolioUseCases portfolios;
    private final DebtorUseCases debtors;
    private final ContactGate contactGate;
    private final DisputeRepository disputes;
    private final Clock clock;

    EligibilityEvaluator(PortfolioUseCases portfolios, DebtorUseCases debtors, ContactGate contactGate,
                         DisputeRepository disputes, Clock clock) {
        this.portfolios = portfolios;
        this.debtors = debtors;
        this.contactGate = contactGate;
        this.disputes = disputes;
        this.clock = clock;
    }

    /** Cache de carteiras para avaliações sucessivas na mesma transação. */
    Batch batch() {
        return new Batch();
    }

    Eligibility evaluate(CollectionCase c) {
        return batch().evaluate(c);
    }

    final class Batch {

        private final Map<UUID, Boolean> operablePortfolios = new HashMap<>();

        Eligibility evaluate(CollectionCase c) {
            if (!c.getStatus().isActive()) {
                return EligibilityPolicy.evaluate(new EligibilityPolicy.Input(c.getStatus(), false, c.balance(), false,
                        false, Map.of()));
            }
            boolean operable = operablePortfolios.computeIfAbsent(c.getPortfolioId(), id -> portfolios.get(id).operable());
            boolean anonymized = ANONYMIZED.equals(debtors.get(c.getDebtorId()).status());
            boolean blockingDispute = c.getId() != null && disputes.existsBlockingByCaseId(c.getId());
            Map<InteractionChannel, ChannelAvailability> channels = anonymized ? Map.of() : channels(c);
            return EligibilityPolicy.evaluate(new EligibilityPolicy.Input(c.getStatus(), operable, c.balance(),
                    blockingDispute, anonymized, channels));
        }

        private Map<InteractionChannel, ChannelAvailability> channels(CollectionCase c) {
            Instant now = Instant.now(clock);
            Map<InteractionChannel, ChannelAvailability> result = new EnumMap<>(InteractionChannel.class);
            for (InteractionChannel channel : InteractionChannel.gateChannels()) {
                ContactDecision decision = contactGate.evaluate(ContactRequest.of(c.getDebtorId(), c.getCreditorId(),
                        channel.name(), null, now, "SYSTEM"));
                boolean restricted = !decision.allowed() && (decision.reasons().stream().anyMatch(RESTRICTIONS::contains)
                        || decision.nextAllowedAt() == null);
                result.put(channel, restricted ? ChannelAvailability.RESTRICTED : ChannelAvailability.AVAILABLE);
            }
            return result;
        }
    }
}
