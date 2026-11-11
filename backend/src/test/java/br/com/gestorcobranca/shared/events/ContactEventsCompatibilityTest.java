package br.com.gestorcobranca.shared.events;

import br.com.gestorcobranca.shared.events.collection.InteractionRecorded;
import br.com.gestorcobranca.shared.events.communication.MessageStatusChanged;
import br.com.gestorcobranca.shared.events.telephony.CallCompleted;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Eventos gravados no registro de publicação antes de {@code contactId}/{@code source} existirem continuam
 * desserializáveis (republicação de pendentes): os campos novos chegam nulos. E eventos novos sobrevivem à ida e
 * volta com os campos preenchidos.
 */
class ContactEventsCompatibilityTest {

    private static final String IDS = """
            "eventId":"%s","tenantId":"%s","occurredAt":"2026-11-10T12:00:00Z","debtorId":"%s","creditorId":"%s"
            """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()).strip();

    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    void interactionWithoutContactAndSource() {
        InteractionRecorded e = mapper.readValue("{" + IDS + """
                ,"interactionId":"%s","caseId":"%s","channel":"PHONE","outcomeCode":"CPC","effectiveContact":true,
                "direction":"OUTBOUND"}""".formatted(UUID.randomUUID(), UUID.randomUUID()), InteractionRecorded.class);

        assertThat(e.contactId()).isNull();
        assertThat(e.source()).isNull();
        assertThat(e.effectiveContact()).isTrue();
    }

    @Test
    void callWithoutContact() {
        CallCompleted e = mapper.readValue("{" + IDS + """
                ,"callId":"%s","direction":"OUTBOUND","result":"ANSWERED","durationSeconds":42}"""
                .formatted(UUID.randomUUID()), CallCompleted.class);

        assertThat(e.contactId()).isNull();
        assertThat(e.durationSeconds()).isEqualTo(42);
    }

    @Test
    void messageWithoutContact() {
        MessageStatusChanged e = mapper.readValue("{" + IDS + """
                ,"messageId":"%s","conversationId":"%s","channel":"WHATSAPP","direction":"INBOUND",
                "status":"RECEIVED"}""".formatted(UUID.randomUUID(), UUID.randomUUID()), MessageStatusChanged.class);

        assertThat(e.contactId()).isNull();
        assertThat(e.status()).isEqualTo("RECEIVED");
    }

    @Test
    void newFieldsRoundTrip() {
        UUID contact = UUID.randomUUID();
        InteractionRecorded original = new InteractionRecorded(UUID.randomUUID(), UUID.randomUUID(),
                java.time.Instant.parse("2026-11-10T12:00:00Z"), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "PHONE", "CPC", true, null, "OUTBOUND", null, contact, "MANUAL");

        InteractionRecorded copy = mapper.readValue(mapper.writeValueAsString(original), InteractionRecorded.class);

        assertThat(copy).isEqualTo(original);
    }
}
