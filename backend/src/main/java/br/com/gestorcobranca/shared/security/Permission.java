package br.com.gestorcobranca.shared.security;

/**
 * Catálogo de permissões do sistema. Perfis (roles) são conjuntos configuráveis destas
 * permissões por tenant. Endpoints e casos de uso exigem permissões, nunca nomes de perfil.
 * <p>
 * Uso em controllers: {@code @PreAuthorize("hasAuthority('CASE_WORK')")}.
 * Novas permissões devem ser adicionadas aqui e aos perfis padrão em
 * {@code identity.domain.model.DefaultRoles}.
 */
public enum Permission {

    // Plataforma (somente operador do SaaS)
    TENANT_MANAGE("Gerenciar assessorias (plataforma)"),

    // Administração da assessoria
    ORG_SETTINGS_MANAGE("Configurar assessoria e unidades"),
    USER_VIEW("Consultar usuários"),
    USER_MANAGE("Gerenciar usuários"),
    ROLE_MANAGE("Gerenciar perfis e permissões"),
    TEAM_MANAGE("Gerenciar equipes"),
    ACCESS_LOG_VIEW("Consultar log de acesso"),
    AUDIT_VIEW("Consultar trilha de auditoria"),
    INTEGRATION_MANAGE("Configurar integrações e provedores"),

    // Credores e carteiras
    CREDITOR_VIEW("Consultar credores"),
    CREDITOR_MANAGE("Gerenciar credores"),
    CONTRACT_MANAGE("Gerenciar contratos de prestação de serviço"),
    PORTFOLIO_VIEW("Consultar carteiras"),
    PORTFOLIO_MANAGE("Gerenciar carteiras"),
    POLICY_VIEW("Consultar políticas de cobrança e negociação"),
    POLICY_MANAGE("Gerenciar políticas de cobrança e negociação"),

    // Ingestão
    IMPORT_VIEW("Consultar importações"),
    IMPORT_EXECUTE("Importar carteiras e títulos"),

    // Devedores e dívidas
    DEBTOR_VIEW("Consultar devedores"),
    DEBTOR_MANAGE("Editar devedores e contatos"),
    DEBTOR_SENSITIVE_VIEW("Visualizar dados pessoais sem máscara"),
    DEBT_TITLE_MANAGE("Suspender, cancelar e ajustar títulos"),

    // Operação de cobrança
    CASE_VIEW("Consultar casos de cobrança"),
    CASE_WORK("Trabalhar casos de cobrança"),
    CASE_ASSIGN("Distribuir e reatribuir casos"),
    QUEUE_MANAGE("Configurar filas de cobrança"),
    INTERACTION_REGISTER("Registrar interações e tabulações"),
    TASK_MANAGE("Gerenciar tarefas e retornos"),
    DISPUTE_MANAGE("Gerenciar contestações"),

    // Negociação e acordos
    NEGOTIATION_SIMULATE("Simular negociações"),
    NEGOTIATION_PROPOSE("Registrar propostas"),
    NEGOTIATION_APPROVE("Aprovar propostas fora da alçada"),
    AGREEMENT_VIEW("Consultar acordos"),
    AGREEMENT_FORMALIZE("Formalizar acordos"),
    AGREEMENT_CANCEL("Cancelar acordos"),

    // Pagamentos
    PAYMENT_VIEW("Consultar pagamentos"),
    PAYMENT_REGISTER_EXTERNAL("Registrar pagamento externo"),
    PAYMENT_REVIEW("Revisar pagamentos informados"),
    PAYMENT_INSTRUCTION_CREATE("Gerar Pix, boleto e link de pagamento"),
    PAYMENT_REVERSE("Registrar estornos"),
    RECONCILIATION_MANAGE("Conciliar pagamentos e divergências"),

    // Comissão e prestação de contas
    COMMISSION_VIEW("Consultar comissões"),
    COMMISSION_MANAGE("Gerenciar regras de comissão"),
    SETTLEMENT_VIEW("Consultar demonstrativos"),
    SETTLEMENT_CLOSE("Fechar e enviar demonstrativos"),

    // Desempenho e remuneração variável dos negociadores (módulo incentive)
    PERFORMANCE_VIEW_OWN("Consultar os próprios indicadores e extratos de comissão"),
    PERFORMANCE_VIEW_TEAM("Consultar indicadores e extratos da equipe"),
    PERFORMANCE_VIEW_ALL("Consultar indicadores e extratos de todos os negociadores"),
    COMMISSION_PLAN_MANAGE("Criar, publicar e simular planos de comissão de negociadores"),
    COMMISSION_STATEMENT_MANAGE("Fechar período, resolver contestações e ajustar extratos"),
    COMMISSION_STATEMENT_APPROVE("Aprovar e exportar extratos de comissão (financeiro)"),
    QUALITY_EVALUATE("Registrar monitorias de qualidade e ocorrências de conduta"),

    // Comunicação
    MESSAGE_SEND("Enviar mensagens"),
    INBOX_VIEW("Consultar caixa de entrada"),
    INBOX_MANAGE("Atribuir e encerrar conversas"),
    TEMPLATE_MANAGE("Gerenciar templates"),
    CHANNEL_MANAGE("Configurar canais de comunicação"),

    // Campanhas
    SEGMENT_MANAGE("Gerenciar segmentos"),
    CAMPAIGN_VIEW("Consultar campanhas"),
    CAMPAIGN_MANAGE("Gerenciar campanhas e réguas"),
    CAMPAIGN_EXECUTE("Ativar, pausar e reprocessar campanhas"),
    EXPERIMENT_MANAGE("Gerenciar testes A/B"),

    // Compliance
    CONTACT_RESTRICTION_MANAGE("Gerenciar bloqueios e opt-out"),
    DATA_SUBJECT_REQUEST_MANAGE("Atender solicitações de titulares (LGPD)"),
    RETENTION_MANAGE("Gerenciar retenção e descarte"),

    // Telefonia
    TELEPHONY_USE("Usar telefonia"),
    TELEPHONY_MANAGE("Configurar telefonia e discador"),

    // Portal de autonegociação
    PORTAL_MANAGE("Configurar portal de autonegociação"),

    // Score
    SCORING_VIEW("Consultar score de propensão"),
    SCORING_MANAGE("Gerenciar modelos de score"),

    // IA
    ASSISTANT_USE("Usar assistente de IA"),
    ASSISTANT_MANAGE("Configurar assistente de IA"),

    // Gestão e relatórios
    DASHBOARD_VIEW("Consultar painéis"),
    REPORT_VIEW("Consultar relatórios"),
    REPORT_EXPORT("Exportar relatórios");

    private final String description;

    Permission(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
