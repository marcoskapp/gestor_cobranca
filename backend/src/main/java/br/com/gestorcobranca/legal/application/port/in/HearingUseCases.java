package br.com.gestorcobranca.legal.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Audiências do processo: agendamento, remarcação, resultado e cancelamento. Participantes
 * internos são usuários; os externos (prepostos, advogados da parte contrária) ficam em texto
 * criptografado. Link de videoconferência somente HTTPS.
 */
public interface HearingUseCases {

    HearingView schedule(UUID lawsuitId, HearingCommand command);

    HearingView reschedule(UUID hearingId, HearingCommand command);

    /** @param outcome AGREEMENT, NO_AGREEMENT, POSTPONED, DEBTOR_ABSENT ou OTHER */
    HearingView recordResult(UUID hearingId, String outcome, String notes);

    HearingView cancel(UUID hearingId, String reason);

    List<HearingView> list(UUID lawsuitId);

    /**
     * @param type CONCILIATION, MEDIATION, INSTRUCTION_AND_JUDGMENT ou OTHER (ignorado na remarcação)
     * @param mode IN_PERSON, VIDEO ou HYBRID
     */
    record HearingCommand(String type, Instant scheduledAt, String mode, String location, String link,
                          Set<UUID> participantUserIds, String externalParticipants) {
    }

    record HearingView(UUID id, UUID lawsuitId, UUID matterId, String type, Instant scheduledAt, String mode,
                       String location, String link, Set<UUID> participantUserIds, String externalParticipants,
                       String status, String outcome, String resultNotes, int rescheduleCount, long version) {
    }
}
