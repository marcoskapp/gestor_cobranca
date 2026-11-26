package br.com.gestorcobranca.ingestion.domain.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Texto pronto do pedido de novo arquivo ao credor (decisão 1497): identifica o arquivo pelo protocolo (data de envio,
 * nome e início do SHA-256) e as linhas pelo número e pela referência do título já mascarada — nunca por nome,
 * documento ou contato do titular. Pede o arquivo de novo sem essas linhas.
 */
public final class CreditorFileRequestText {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    /** Caracteres do SHA-256 no protocolo (o bastante para o credor achar o arquivo). */
    public static final int PROTOCOL_HASH = 12;

    private CreditorFileRequestText() {
    }

    /** Linha do arquivo com o titular: número e referência do título mascarada. */
    public record RequestLine(long rowNumber, String reference) {
    }

    /**
     * @param more linhas do titular além das listadas (o registro guarda até 500 por bloqueio)
     */
    public static String compose(String creditorName, String fileName, LocalDate sentOn, String sha256,
                                 List<RequestLine> lines, int more) {
        StringBuilder text = new StringBuilder()
                .append("Assunto: Arquivo de carga recusado — titular com dados eliminados (LGPD)\n\n")
                .append("Prezados(as), ").append(creditorName).append(",\n\n")
                .append("O arquivo \"").append(fileName).append("\" enviado em ").append(DATE.format(sentOn))
                .append(" (protocolo ").append(protocol(sha256)).append(") não pôde ser usado: ele traz linhas de um ")
                .append("titular cujos dados foram eliminados em nosso sistema, em atendimento à Lei Geral de Proteção ")
                .append("de Dados.\n\nLinhas do arquivo com esse titular:\n");
        lines.forEach(l -> text.append("- Linha ").append(l.rowNumber()).append(": título ").append(l.reference())
                .append('\n'));
        if (more > 0) {
            text.append("- e mais ").append(more).append(more == 1 ? " linha\n" : " linhas\n");
        }
        return text.append("\nPedimos que enviem um novo arquivo sem essas linhas; as demais podem vir normalmente (o ")
                .append("que já foi importado não se repete).\n\n")
                .append("Por segurança, este pedido não traz nome, documento nem contato do titular.\n")
                .toString();
    }

    public static String protocol(String sha256) {
        return sha256 == null ? "—" : sha256.substring(0, Math.min(PROTOCOL_HASH, sha256.length()));
    }
}
