package br.com.gestorcobranca.compliance.application.port.in;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Cadastro de operadores e suboperadores (LGPD art. 37 e 39): quem trata dados dos devedores em nome da assessoria,
 * para quê, em que país (com a garantia do art. 33 fora do Brasil) e sob qual contrato. A chave do provedor casa com a
 * das fontes de compartilhamento ({@code shared.privacy}); o rascunho de resposta ao titular usa o nome, a finalidade e
 * o país cadastrados. Alterar exige acesso a todos os credores (o cadastro é da assessoria) e grava auditoria.
 */
public interface DataProcessorUseCases {

    List<ProcessorView> list();

    ProcessorView get(UUID id);

    /** Chave já cadastrada → {@code data_processor.duplicate} (409). */
    ProcessorView register(RegisterProcessorCommand command);

    ProcessorView update(UUID id, ProcessorDetails details);

    /**
     * Prestadores configurados na assessoria (contas de canal, telefonia, Pix, IA) sem cadastro. Lista contas de todos
     * os credores: exige o alcance de todos os credores ({@code ScopeAccessDeniedException}, 403), como alterar.
     */
    List<RecipientInUseView> unregisteredRecipients();

    /**
     * @param role              OPERATOR, SUB_OPERATOR ou JOINT_CONTROLLER
     * @param countryCode       ISO 3166-1 alfa-2 (ex.: BR, US, IE)
     * @param transferSafeguard obrigatória fora do Brasil: ADEQUACY_DECISION, SPECIFIC_CONTRACTUAL_CLAUSES,
     *                          STANDARD_CONTRACTUAL_CLAUSES, GLOBAL_CORPORATE_RULES, SEALS_AND_CERTIFICATES,
     *                          ANPD_AUTHORIZATION, SPECIFIC_CONSENT ou LEGAL_NECESSITY
     */
    record ProcessorDetails(String name, String role, String purpose, String dataCategories, String countryCode,
                            String transferSafeguard, String contractReference, LocalDate validFrom,
                            LocalDate validUntil, boolean active) {
    }

    record RegisterProcessorCommand(String providerKey, ProcessorDetails details) {
    }

    /**
     * @param current ativo e com contrato vigente hoje
     */
    record ProcessorView(UUID id, String providerKey, String name, String role, String purpose, String dataCategories,
                         String countryCode, boolean internationalTransfer, String transferSafeguard,
                         String contractReference, LocalDate validFrom, LocalDate validUntil, boolean active,
                         boolean current, Instant updatedAt) {
    }

    /**
     * @param purpose código da finalidade ({@code DataSharingPurpose})
     * @param source  área de origem (ex.: "Mensagens")
     */
    record RecipientInUseView(String providerKey, String name, String purpose, String source) {
    }
}
