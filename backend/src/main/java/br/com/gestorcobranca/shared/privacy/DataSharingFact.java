package br.com.gestorcobranca.shared.privacy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Um destinatário com quem dados pessoais do titular foram compartilhados, como o módulo dono registrou: só ids, nome
 * de empresa, finalidade, período e contagem — nunca conteúdo de mensagem, telefone, e-mail ou documento.
 *
 * @param recipientType tipo do destinatário
 * @param recipientKey  chave estável do destinatário ({@code <modulo>.<provedor>} ou {@code <modulo>.<tipo>:<id>}, ex.:
 *                      {@code communication.whatsapp_cloud}, {@code legal.law_firm:<uuid>}); é a chave do cadastro de
 *                      operadores no compliance
 * @param recipientId   id do registro do destinatário no módulo (credor, escritório, conta, token), quando houver
 * @param recipientName nome de exibição (empresa, conta ou plataforma)
 * @param purpose       finalidade
 * @param firstAt       primeiro uso registrado
 * @param lastAt        último uso registrado
 * @param count         quantidade de registros (mensagens, chamadas, cobranças, casos, títulos…)
 * @param countLabel    a quantidade por extenso ("12 mensagens"), sem dado pessoal
 * @param link          tela que abre a evidência (nulo se não houver)
 */
public record DataSharingFact(DataRecipientType recipientType, String recipientKey, UUID recipientId,
                              String recipientName, DataSharingPurpose purpose, Instant firstAt, Instant lastAt,
                              long count, String countLabel, DataSharingLink link) {

    /** {@code <modulo>.<nome>} com sufixo opcional {@code :<uuid>}. */
    public static final Pattern KEY = Pattern.compile("[a-z]+\\.[a-z_]+(:[0-9a-f-]{36})?");

    public DataSharingFact {
        Objects.requireNonNull(recipientType, "recipientType");
        Objects.requireNonNull(recipientKey, "recipientKey");
        if (!KEY.matcher(recipientKey).matches()) {
            throw new IllegalArgumentException("Chave de destinatário inválida: " + recipientKey);
        }
        Objects.requireNonNull(recipientName, "recipientName");
        Objects.requireNonNull(purpose, "purpose");
        if (count < 0) {
            throw new IllegalArgumentException("count");
        }
    }

    /** "1 mensagem" / "12 mensagens". */
    public static String counted(long count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
    }
}
