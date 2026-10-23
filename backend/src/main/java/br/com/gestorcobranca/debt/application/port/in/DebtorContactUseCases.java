package br.com.gestorcobranca.debt.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Contatos do devedor (telefone, e-mail, endereço). Valores são validados e normalizados
 * (telefone E.164 brasileiro, e-mail minúsculo), gravados criptografados e exibidos mascarados.
 * O mesmo valor não é cadastrado duas vezes para o mesmo devedor.
 * <p>
 * Todas as operações exigem que o devedor seja visível ao ator; operações sobre um contato
 * recebem também o devedor, e um contato de outro devedor é tratado como inexistente.
 */
public interface DebtorContactUseCases {

    /** Contato já existente para o devedor → conflito {@code contact.duplicate}. */
    ContactView add(UUID debtorId, AddContactCommand command);

    /** Altera classificação, WhatsApp e prioridade (o valor é imutável: outro valor é outro contato). */
    ContactView update(UUID debtorId, UUID contactId, UpdateContactCommand command);

    ContactView markInvalid(UUID debtorId, UUID contactId, String reason);

    ContactView markValid(UUID debtorId, UUID contactId);

    /** Contatos do devedor com valores mascarados, por tipo e prioridade. */
    List<ContactView> list(UUID debtorId);

    /** Valor completo. Exige {@code DEBTOR_SENSITIVE_VIEW}; registra {@code SENSITIVE_DATA_VIEWED}. */
    RevealedContact revealContact(UUID debtorId, UUID contactId);

    /**
     * <strong>Uso interno de outros módulos (communication, telephony); não exposto via web.</strong>
     * Contatos utilizáveis (não inválidos) de um tipo, descriptografados exclusivamente para o
     * envio/discagem, em ordem de prioridade. O chamador não deve persistir nem registrar em log
     * os valores; deve referenciar o contato pelo {@code contactId}.
     *
     * @param type PHONE, EMAIL ou ADDRESS
     */
    List<ChannelContact> contactsForChannel(UUID debtorId, String type);

    /**
     * <strong>Uso interno de outros módulos (compliance); não exposto via web.</strong>
     * Normaliza um telefone ou e-mail como no cadastro de contatos e devolve o blind index do
     * tenant corrente e a forma mascarada — permite restringir ou localizar um contato sem
     * guardar o valor em claro. Valor inválido → exceção de negócio ({@code contact.*_invalid}).
     *
     * @param type PHONE ou EMAIL
     */
    ContactFingerprint fingerprint(String type, String value);

    /**
     * @param type   PHONE, EMAIL ou ADDRESS
     * @param label  MOBILE, HOME, WORK ou OTHER (padrão OTHER)
     * @param source MANUAL ou DEBTOR (cargas usam {@code DebtIngestionPort}); padrão MANUAL
     * @param priority ordem de uso (menor primeiro); nulo = após os existentes do mesmo tipo
     */
    record AddContactCommand(String type, String value, String label, boolean whatsapp, Integer priority,
                             String source) {
    }

    record UpdateContactCommand(String label, boolean whatsapp, int priority) {
    }

    record ContactView(UUID id, UUID debtorId, String type, String maskedValue, String label, boolean whatsapp,
                       String source, String status, int priority, Instant lastVerifiedAt, String invalidReason) {
    }

    record RevealedContact(UUID contactId, UUID debtorId, String type, String value) {
    }

    /** @param hash blind index (finalidade {@code contact.phone} ou {@code contact.email}) */
    record ContactFingerprint(String type, String hash, String maskedValue) {
    }

    record ChannelContact(UUID contactId, String type, String value, String label, boolean whatsapp, int priority,
                          String status) {
    }
}
