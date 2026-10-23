package br.com.gestorcobranca.identity.domain.model;

import br.com.gestorcobranca.shared.security.Permission;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static br.com.gestorcobranca.shared.security.Permission.*;

/**
 * Perfis criados no provisionamento de cada assessoria (M01): administrador, gestor,
 * supervisor, operador, financeiro e auditor. As permissões podem ser ajustadas depois por quem
 * tem {@code ROLE_MANAGE}.
 * <p>
 * Remuneração variável: o gestor fecha períodos e ajusta extratos ({@code COMMISSION_STATEMENT_MANAGE});
 * o financeiro aprova ({@code COMMISSION_STATEMENT_APPROVE}). A segregação também é verificada em
 * tempo de execução (quem fecha ou ajusta não aprova), inclusive para o administrador, que tem ambas.
 */
public final class DefaultRoles {

    public static final String ADMIN = "ADMIN";
    public static final String GESTOR = "GESTOR";
    public static final String SUPERVISOR = "SUPERVISOR";
    public static final String OPERADOR = "OPERADOR";
    public static final String FINANCEIRO = "FINANCEIRO";
    public static final String AUDITOR = "AUDITOR";
    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    public record Definition(String code, String name, String description, Set<Permission> permissions) {
    }

    private DefaultRoles() {
    }

    public static List<Definition> forTenant() {
        Set<Permission> admin = EnumSet.allOf(Permission.class);
        admin.remove(TENANT_MANAGE);

        Set<Permission> operador = EnumSet.of(
                CREDITOR_VIEW, PORTFOLIO_VIEW, POLICY_VIEW,
                DEBTOR_VIEW, CASE_VIEW, CASE_WORK, INTERACTION_REGISTER, TASK_MANAGE,
                NEGOTIATION_SIMULATE, NEGOTIATION_PROPOSE, AGREEMENT_VIEW, AGREEMENT_FORMALIZE,
                PAYMENT_VIEW, PAYMENT_INSTRUCTION_CREATE, PAYMENT_REGISTER_EXTERNAL,
                MESSAGE_SEND, INBOX_VIEW, TELEPHONY_USE, ASSISTANT_USE, SCORING_VIEW, PERFORMANCE_VIEW_OWN);

        Set<Permission> supervisor = EnumSet.copyOf(operador);
        supervisor.addAll(EnumSet.of(USER_VIEW, TEAM_MANAGE, CASE_ASSIGN, QUEUE_MANAGE, DISPUTE_MANAGE,
                NEGOTIATION_APPROVE, AGREEMENT_CANCEL, INBOX_MANAGE, CONTACT_RESTRICTION_MANAGE,
                CAMPAIGN_VIEW, DASHBOARD_VIEW, REPORT_VIEW, DEBTOR_MANAGE, IMPORT_VIEW, PERFORMANCE_VIEW_TEAM,
                QUALITY_EVALUATE));

        Set<Permission> gestor = EnumSet.copyOf(supervisor);
        gestor.addAll(EnumSet.of(CREDITOR_MANAGE, CONTRACT_MANAGE, PORTFOLIO_MANAGE, POLICY_MANAGE, IMPORT_EXECUTE,
                DEBT_TITLE_MANAGE, SEGMENT_MANAGE, CAMPAIGN_MANAGE, CAMPAIGN_EXECUTE, EXPERIMENT_MANAGE,
                TEMPLATE_MANAGE, COMMISSION_VIEW, SETTLEMENT_VIEW, REPORT_EXPORT, SCORING_MANAGE,
                PORTAL_MANAGE, ACCESS_LOG_VIEW, DEBTOR_SENSITIVE_VIEW, PERFORMANCE_VIEW_ALL, COMMISSION_PLAN_MANAGE,
                COMMISSION_STATEMENT_MANAGE));

        Set<Permission> financeiro = EnumSet.of(
                CREDITOR_VIEW, PORTFOLIO_VIEW, POLICY_VIEW, DEBTOR_VIEW, CASE_VIEW, AGREEMENT_VIEW,
                PAYMENT_VIEW, PAYMENT_REGISTER_EXTERNAL, PAYMENT_REVIEW, PAYMENT_INSTRUCTION_CREATE, PAYMENT_REVERSE,
                RECONCILIATION_MANAGE, COMMISSION_VIEW, COMMISSION_MANAGE, SETTLEMENT_VIEW, SETTLEMENT_CLOSE,
                DASHBOARD_VIEW, REPORT_VIEW, REPORT_EXPORT, PERFORMANCE_VIEW_ALL, COMMISSION_STATEMENT_APPROVE);

        Set<Permission> auditor = EnumSet.of(
                USER_VIEW, ACCESS_LOG_VIEW, AUDIT_VIEW, CREDITOR_VIEW, PORTFOLIO_VIEW, POLICY_VIEW, IMPORT_VIEW,
                DEBTOR_VIEW, CASE_VIEW, AGREEMENT_VIEW, PAYMENT_VIEW, COMMISSION_VIEW, SETTLEMENT_VIEW,
                CAMPAIGN_VIEW, INBOX_VIEW, SCORING_VIEW, DASHBOARD_VIEW, REPORT_VIEW, PERFORMANCE_VIEW_ALL);

        return List.of(
                new Definition(ADMIN, "Administrador", "Acesso total à assessoria", admin),
                new Definition(GESTOR, "Gestor", "Gestão de credores, carteiras, estratégias e resultados", gestor),
                new Definition(SUPERVISOR, "Supervisor", "Coordenação de equipes, distribuição e aprovações", supervisor),
                new Definition(OPERADOR, "Operador", "Atendimento e negociação com devedores", operador),
                new Definition(FINANCEIRO, "Financeiro", "Pagamentos, conciliação, comissão e prestação de contas", financeiro),
                new Definition(AUDITOR, "Auditor", "Consulta e auditoria, somente leitura", auditor));
    }

    public static Definition platformAdmin() {
        return new Definition(PLATFORM_ADMIN, "Administrador da plataforma", "Provisionamento de assessorias",
                EnumSet.of(TENANT_MANAGE, USER_VIEW, USER_MANAGE, ROLE_MANAGE, ACCESS_LOG_VIEW, AUDIT_VIEW));
    }
}
