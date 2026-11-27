package br.com.gestorcobranca.compliance.application.port.spi;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Evidência do pedido de novo arquivo ao credor (SEG-20261124-D01h, decisão 1497): os lotes da carga bloqueados por
 * titular eliminado que apontam para o aviso da eliminação ao credor, e se o pedido de novo arquivo foi registrado.
 * Declarada pelo compliance (dono do aviso) e implementada pelo ingestion (dono do lote e do pedido); só o aviso ao
 * credor consulta ({@code ArchitectureTest}). Sem dado pessoal: lote, protocolo do arquivo, quantidades e o registro.
 * Na transação do chamador, que já conferiu o acesso ao aviso.
 */
public interface NewFileRequests {

    /** Lotes listados por aviso (o total informa quantos há; o restante fica na lista de lotes da importação). */
    int MAX_BATCHES = 50;

    /**
     * Lotes do credor bloqueados com o aviso, até {@link #MAX_BATCHES}: primeiro os sem pedido registrado, do mais
     * recente ao mais antigo (os que pedem ação), depois os com pedido.
     */
    Evidence ofNotice(UUID noticeId, UUID creditorId);

    /** @param total quantos lotes há (pode passar de {@code items.size()}, que vai até {@link #MAX_BATCHES}) */
    record Evidence(List<NewFileRequest> items, int total) {
        public Evidence {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public static Evidence none() {
            return new Evidence(List.of(), 0);
        }
    }

    /**
     * @param protocol   protocolo do arquivo no texto do pedido (início do SHA-256, sem nome nem documento)
     * @param lines      linhas do titular no arquivo (as distintas dos registros de bloqueio, como a tela do lote)
     * @param inProgress o lote ainda processa (o pedido só é registrado depois)
     * @param sentAt     quando o pedido foi enviado ao credor (nulo enquanto não registrado)
     * @param channel    canal do envio ({@code EMAIL}, {@code PHONE}, {@code LETTER}, {@code MEETING}, {@code OTHER})
     * @param sentBy     quem registrou o envio
     */
    record NewFileRequest(UUID batchId, String protocol, Instant blockedAt, int lines, boolean inProgress,
                          Instant sentAt, String channel, UUID sentBy) {
    }
}
