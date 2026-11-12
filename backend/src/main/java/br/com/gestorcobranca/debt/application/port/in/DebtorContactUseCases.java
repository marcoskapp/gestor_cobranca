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
     * <strong>Uso interno de outros módulos (communication); não exposto via web.</strong> Como
     * {@link #fingerprint}, mas devolve vazio para valor inválido em vez de lançar exceção — para
     * endereços recebidos de provedores (webhooks), em que um valor fora do padrão é ignorado sem
     * desfazer a transação do chamador.
     */
    java.util.Optional<ContactFingerprint> tryFingerprint(String type, String value);

    /**
     * @param type   PHONE, EMAIL ou ADDRESS
     * @param label  MOBILE, HOME, WORK ou OTHER (padrão OTHER)
     * @param source MANUAL ou DEBTOR (cargas usam {@code DebtIngestionPort}); padrão MANUAL
     * @param priority ordem de uso (menor primeiro); nulo = após os existentes do mesmo tipo
     * @param personName nome da pessoa de contato (B2, opcional, até 120 caracteres)
     * @param personRole FINANCE, ACCOUNTS_PAYABLE, OWNER_PARTNER, LEGAL ou OTHER (opcional)
     * @param decisionMaker a pessoa de contato decide pelo devedor
     */
    record AddContactCommand(String type, String value, String label, boolean whatsapp, Integer priority,
                             String source, String personName, String personRole, boolean decisionMaker) {

        public AddContactCommand(String type, String value, String label, boolean whatsapp, Integer priority,
                                 String source) {
            this(type, value, label, whatsapp, priority, source, null, null, false);
        }
    }

    /**
     * Classificação e pessoa de contato.
     *
     * @param personName nulo mantém o nome atual (a tela só conhece o nome mascarado); vazio remove
     * @param personRole papel (nulo remove)
     */
    record UpdateContactCommand(String label, boolean whatsapp, int priority, String personName, String personRole,
                                boolean decisionMaker) {
    }

    /**
     * @param maskedPersonName nome da pessoa de contato mascarado como o valor ("A***"); o nome completo vem na revelação
     * @param personRole       papel da pessoa de contato (nulo se não informado)
     * @param receipt          recebimento por carga (selo "Novo" e reenvio); nulo se o contato nunca veio de carga
     */
    record ContactView(UUID id, UUID debtorId, String type, String maskedValue, String label, boolean whatsapp,
                       String source, String status, int priority, Instant lastVerifiedAt, String invalidReason,
                       String maskedPersonName, String personRole, boolean decisionMaker, ContactReceiptView receipt) {
    }

    /**
     * Contato recebido em carga (docs/tecnico/desenho/contatos-recebidos.md §4). Só tipo e datas, sem valor.
     *
     * @param novelty        NEW (ainda sem tentativa), NEW_ANSWERED ou NEW_NO_ANSWER (já testado, dentro da janela);
     *                       nulo = sem selo (nunca novo, janela encerrada ou contato inválido)
     * @param receivedAt     quando a carga criou o contato (nulo se criado fora de carga e só reenviado depois)
     * @param noveltyUntil   fim da janela de novidade (nulo = nunca foi novo)
     * @param confirmedAt    último reenvio pelo credor (nulo se nunca reenviado)
     * @param firstAttemptAt primeira tentativa por este contato
     */
    record ContactReceiptView(String novelty, Instant receivedAt, Instant noveltyUntil, Instant confirmedAt,
                              Instant firstAttemptAt) {
    }

    /** @param personName nome completo da pessoa de contato (nulo se não informado) */
    record RevealedContact(UUID contactId, UUID debtorId, String type, String value, String personName) {
    }

    /** @param hash blind index (finalidade {@code contact.phone} ou {@code contact.email}) */
    record ContactFingerprint(String type, String hash, String maskedValue) {
    }

    /**
     * @param newPending contato novo recebido na carga ainda sem tentativa, dentro da janela (o discador o usa primeiro)
     */
    record ChannelContact(UUID contactId, String type, String value, String label, boolean whatsapp, int priority,
                          String status, boolean newPending) {
    }
}
