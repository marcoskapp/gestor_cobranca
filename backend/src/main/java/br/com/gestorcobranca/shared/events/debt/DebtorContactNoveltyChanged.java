package br.com.gestorcobranca.shared.events.debt;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * O fim da novidade dos contatos do devedor mudou (docs/tecnico/desenho/contatos-recebidos.md §3): contato novo recebido
 * numa carga para devedor já existente, primeira tentativa por um contato novo, contato novo invalidado/revalidado ou
 * janela vencida (varredura diária). Publicado pelo debt; consumido pelo collection (critério de fila "só devedores com
 * contato novo") e pelo campaign (critério de segmento "com contato novo").
 * <p>
 * Sem valor de contato nem documento: só tipos e datas.
 *
 * @param newContactUntil maior fim de janela entre os contatos novos ainda sem tentativa; nulo = nenhum contato novo
 *                        pendente (encerra)
 * @param channels        tipos com contato novo pendente: PHONE, EMAIL, ADDRESS, e WHATSAPP quando o telefone novo tem
 *                        WhatsApp (vazio quando {@code newContactUntil} é nulo)
 * @param importBatchId   carga que trouxe o contato novo; nulo fora da carga (uso, varredura, invalidação)
 */
public record DebtorContactNoveltyChanged(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID debtorId,
        Instant newContactUntil,
        List<String> channels,
        UUID importBatchId) implements IntegrationEvent {

    public DebtorContactNoveltyChanged {
        channels = channels == null ? List.of() : List.copyOf(channels);
    }
}
