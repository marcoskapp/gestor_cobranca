package br.com.gestorcobranca.identity.domain.service;

import br.com.gestorcobranca.shared.logging.access.AccessEventType;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lista branca de "Minha atividade" do portal do credor (fatia 7; RS-47 a RS-49): o que a pessoa do credor fez,
 * lido da auditoria ({@code shared.audit_event}, ator {@code CREDITOR}) e do log de acesso. Só o que está aqui aparece,
 * como um código curto e uma referência (tipo e id) — nunca o antes/depois, metadados, motivo, nome do ator, IP ou
 * sessão. Toda ação de auditoria com "creditor" no nome e todo tipo do log de acesso têm decisão explícita (incluído ou
 * excluído, com o motivo): {@code CreditorActivityCatalogTest} falha quando surge um sem decisão.
 */
public final class CreditorActivityCatalog {

    /** Código curto exposto ao portal (o texto em linguagem do credor fica na tela). */
    public enum Activity {
        STATEMENT_ACKNOWLEDGED, STATEMENT_CONTESTED, DISPUTE_RESPONDED, PAYMENT_REPORTED, SIGNED_IN, SIGNED_OUT,
        PASSWORD_CHANGED, MFA_ENROLLED, FILE_DOWNLOADED
    }

    /** Ações de auditoria da própria pessoa do credor que aparecem. */
    private static final Map<String, Activity> AUDIT = Map.of(
            "settlement_statement.acknowledged", Activity.STATEMENT_ACKNOWLEDGED,
            "settlement_statement.contested", Activity.STATEMENT_CONTESTED,
            "dispute.creditor_responded", Activity.DISPUTE_RESPONDED,
            "payment.creditor_portal_reported", Activity.PAYMENT_REPORTED);

    /**
     * Ações de auditoria com decisão de <b>não</b> aparecer: feitas pela assessoria ou pelo sistema sobre o credor ou
     * sobre a pessoa do credor (decisão da fatia 7: ações da assessoria ficam fora de "Minha atividade"), ou repetidas.
     */
    private static final Set<String> AUDIT_EXCLUDED = Set.of(
            // duplica payment.creditor_portal_reported (o mesmo informe gravado pelo caminho comum do payment)
            "payment.informed",
            // revisão, alocação e conciliação do pagamento (PaymentRecorder): ações da assessoria ou do sistema
            "payment.confirmed", "payment.rejected", "payment.reversed", "payment.reallocated", "payment.reconciled",
            "payment.approved",
            // suspensão do título pela carga do credor (H07 2b): gravada pelo processamento da carga (sistema)
            "debt_title.suspended_by_creditor_report",
            "payment_divergence.opened", "payment_divergence.resolved", "payment_instruction.created",
            // cadastro do credor, contatos de contestação e liberação do portal: ações da assessoria
            "creditor.created", "creditor.updated", "creditor.status_changed",
            "creditor_dispute_contact.created", "creditor_dispute_contact.updated", "creditor_dispute_contact.deactivated",
            "creditor_portal.enabled", "creditor_portal.disabled",
            // aviso de eliminação ao credor (D01h): aberto e remetido pela assessoria (compliance)
            "creditor_erasure_notice.opened", "creditor_erasure_notice.notified",
            // convite, perfil, revogação e redefinição pela assessoria; ativação e senha gravadas no login (ator do
            // sistema) — a pessoa vê a ativação e a troca de senha pelo log de acesso (MFA_ENROLLED, PASSWORD_CHANGED)
            "creditor_user.invited", "creditor_user.invitation_issued", "creditor_user.invitation_resent",
            "creditor_user.invite_refused", "creditor_user.access_reset", "creditor_user.role_changed",
            "creditor_user.revoked", "creditor_user.activated", "creditor_user.password_changed",
            "creditor_user.password_reset_issued",
            // pedidos da assessoria ao credor e a autorização jurídica: ações da assessoria
            "dispute.creditor_requested", "dispute.creditor_request_cancelled", "dispute.creditor_request_overdue",
            "creditor_authorization.requested", "creditor_authorization.decided");

    /** Tipos do log de acesso da própria pessoa que aparecem ({@code FILE_DOWNLOADED} só com a origem do portal). */
    private static final Map<AccessEventType, Activity> ACCESS = new EnumMap<>(Map.of(
            AccessEventType.LOGIN_SUCCESS, Activity.SIGNED_IN,
            AccessEventType.LOGOUT, Activity.SIGNED_OUT,
            AccessEventType.PASSWORD_CHANGED, Activity.PASSWORD_CHANGED,
            AccessEventType.MFA_ENROLLED, Activity.MFA_ENROLLED,
            AccessEventType.FILE_DOWNLOADED, Activity.FILE_DOWNLOADED));

    /**
     * Tipos do log de acesso que não aparecem: falhas e recusas (segurança, investigadas pela assessoria), renovação de
     * sessão (ruído), eventos de outros atores (usuário interno, devedor, integração, plataforma).
     */
    private static final Set<AccessEventType> ACCESS_EXCLUDED = EnumSet.of(
            AccessEventType.LOGIN_FAILURE, AccessEventType.LOGIN_BLOCKED, AccessEventType.MFA_CHALLENGE,
            AccessEventType.MFA_SUCCESS, AccessEventType.MFA_FAILURE, AccessEventType.MFA_RESET,
            AccessEventType.TOKEN_REFRESHED, AccessEventType.TOKEN_REUSE_DETECTED, AccessEventType.SESSION_REVOKED,
            AccessEventType.SESSION_EXPIRED, AccessEventType.PASSWORD_RESET_REQUESTED,
            AccessEventType.PASSWORD_RESET_COMPLETED, AccessEventType.USER_CREATED, AccessEventType.USER_UPDATED,
            AccessEventType.USER_DISABLED, AccessEventType.USER_ENABLED, AccessEventType.USER_UNLOCKED,
            AccessEventType.ROLE_CHANGED, AccessEventType.PERMISSION_CHANGED, AccessEventType.SCOPE_CHANGED,
            AccessEventType.ACCESS_DENIED, AccessEventType.SCOPE_DENIED, AccessEventType.UNAUTHENTICATED,
            AccessEventType.SENSITIVE_DATA_VIEWED, AccessEventType.PERSONAL_DATA_SEARCHED,
            AccessEventType.COMPENSATION_DATA_VIEWED, AccessEventType.DATA_EXPORTED, AccessEventType.WEBHOOK_REJECTED,
            AccessEventType.DISPUTE_LINK_REJECTED, AccessEventType.MALWARE_DETECTED,
            AccessEventType.INBOUND_MAIL_REJECTED, AccessEventType.PORTAL_LOGIN_SUCCESS,
            AccessEventType.PORTAL_LOGIN_FAILURE, AccessEventType.PORTAL_CODE_REQUESTED,
            AccessEventType.PORTAL_LINK_ISSUED, AccessEventType.PORTAL_LOCKED, AccessEventType.PORTAL_LOGOUT,
            AccessEventType.PORTAL_SESSION_REVOKED, AccessEventType.PORTAL_PRIVACY_ACKNOWLEDGED,
            AccessEventType.RATE_LIMITED, AccessEventType.INTEGRATION_ENDPOINT_CHANGED,
            AccessEventType.SUPPORT_DATA_QUERIED, AccessEventType.SUPPORT_TICKET_OPENED,
            // a própria consulta da atividade do time não vira atividade (registro de quem consultou)
            AccessEventType.USER_DIRECTORY_QUERIED, AccessEventType.PUBLIC_API_REQUEST,
            AccessEventType.INTEGRATION_TOKEN_REJECTED, AccessEventType.INTEGRATION_TOKEN_CREATED,
            AccessEventType.INTEGRATION_TOKEN_REVOKED, AccessEventType.INTEGRATION_TOKEN_ROTATED,
            AccessEventType.PLATFORM_TENANT_DATA_VIEWED, AccessEventType.INTEGRATION_TOKEN_PAUSED,
            AccessEventType.INTEGRATION_TOKEN_RESUMED);

    /** Tipo do registro citado → referência exposta (lista branca; fora dela, sem referência). */
    private static final Map<String, String> REFERENCES = Map.of(
            "settlement_statement", "STATEMENT",
            "dispute", "DISPUTE",
            "payment", "PAYMENT",
            "statement_export", "STATEMENT_FILE",
            "dispute_evidence", "DISPUTE_DOCUMENT");

    /** Origem exigida no {@code FILE_DOWNLOADED} (downloads feitos no portal do credor). */
    public static final String PORTAL_ORIGIN = "creditor_portal";

    private CreditorActivityCatalog() {
    }

    public static Optional<Activity> audit(String action) {
        return Optional.ofNullable(AUDIT.get(action));
    }

    public static Optional<Activity> access(AccessEventType type) {
        return Optional.ofNullable(ACCESS.get(type));
    }

    /** Referência do registro citado pela atividade (tipo exposto), ou vazio fora da lista branca. */
    public static Optional<String> reference(String entityType) {
        return Optional.ofNullable(entityType == null ? null : REFERENCES.get(entityType));
    }

    public static Set<String> auditActions() {
        return AUDIT.keySet();
    }

    public static Set<String> excludedAuditActions() {
        return AUDIT_EXCLUDED;
    }

    public static Set<AccessEventType> accessTypes() {
        return ACCESS.keySet();
    }

    public static Set<AccessEventType> excludedAccessTypes() {
        return ACCESS_EXCLUDED;
    }
}
