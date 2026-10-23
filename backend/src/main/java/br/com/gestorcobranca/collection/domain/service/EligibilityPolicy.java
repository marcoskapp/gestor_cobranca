package br.com.gestorcobranca.collection.domain.service;

import br.com.gestorcobranca.collection.domain.model.CaseBalance;
import br.com.gestorcobranca.collection.domain.model.CaseStatus;
import br.com.gestorcobranca.collection.domain.model.Eligibility;
import br.com.gestorcobranca.collection.domain.model.EligibilityReason;
import br.com.gestorcobranca.collection.domain.model.InteractionChannel;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Elegibilidade do caso para contato de cobrança (RB02). O caso é elegível quando, ao mesmo
 * tempo:
 * <ol>
 *   <li>está ativo e fora de retenção (não suspenso, não jurídico, sem contestação bloqueante);</li>
 *   <li>a carteira está ativa e na vigência;</li>
 *   <li>há título em aberto não bloqueado por contestação;</li>
 *   <li>o devedor não foi anonimizado;</li>
 *   <li>ao menos um canal de contato não está restrito (opt-out, bloqueio) para o devedor e o
 *       credor.</li>
 * </ol>
 * Janela de horário, feriados e limites de frequência <strong>não</strong> entram: são regras
 * do momento do contato, verificadas pelo porteiro de contato (compliance) a cada tentativa.
 */
public final class EligibilityPolicy {

    private EligibilityPolicy() {
    }

    /** Disponibilidade de um canal para o devedor/credor, segundo as restrições de contato. */
    public enum ChannelAvailability { AVAILABLE, RESTRICTED }

    /**
     * @param channels resultado das restrições por canal; vazio quando não avaliado (caso
     *                 encerrado ou já inelegível por outro motivo estrutural)
     */
    public record Input(CaseStatus status, boolean portfolioOperable, CaseBalance balance, boolean blockingDisputeOpen,
                        boolean debtorAnonymized, Map<InteractionChannel, ChannelAvailability> channels) {

        public Input {
            channels = channels == null ? Map.of() : Map.copyOf(channels);
        }
    }

    public static Eligibility evaluate(Input in) {
        if (!in.status().isActive()) {
            return Eligibility.of(EnumSet.of(EligibilityReason.CASE_CLOSED), Set.of());
        }
        EnumSet<EligibilityReason> reasons = EnumSet.noneOf(EligibilityReason.class);
        if (in.status() == CaseStatus.SUSPENDED) {
            reasons.add(EligibilityReason.CASE_SUSPENDED);
        }
        if (in.status() == CaseStatus.LEGAL) {
            reasons.add(EligibilityReason.CASE_LEGAL);
        }
        if (in.status() == CaseStatus.DISPUTED || in.blockingDisputeOpen()) {
            reasons.add(EligibilityReason.DISPUTE_OPEN);
        }
        if (!in.portfolioOperable()) {
            reasons.add(EligibilityReason.PORTFOLIO_NOT_OPERABLE);
        }
        CaseBalance balance = in.balance();
        if (balance.openTitles() == 0) {
            reasons.add(EligibilityReason.NO_OPEN_TITLES);
        } else if (balance.disputedTitles() >= balance.openTitles()) {
            reasons.add(EligibilityReason.TITLES_DISPUTED);
        }
        if (in.debtorAnonymized()) {
            reasons.add(EligibilityReason.DEBTOR_ANONYMIZED);
        }
        EnumSet<InteractionChannel> contactable = EnumSet.noneOf(InteractionChannel.class);
        in.channels().forEach((channel, availability) -> {
            if (availability == ChannelAvailability.AVAILABLE) {
                contactable.add(channel);
            }
        });
        if (!in.channels().isEmpty() && contactable.isEmpty() && !in.debtorAnonymized()) {
            reasons.add(EligibilityReason.CONTACT_RESTRICTED);
        }
        return Eligibility.of(reasons, contactable);
    }
}
