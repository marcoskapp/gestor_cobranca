package br.com.gestorcobranca.identity.domain.model.creditor;

import br.com.gestorcobranca.shared.security.Permission;

import java.util.EnumSet;
import java.util.Set;

import static br.com.gestorcobranca.shared.security.Permission.CREDITOR_ACTIVITY_VIEW;
import static br.com.gestorcobranca.shared.security.Permission.CREDITOR_DISPUTE_RESPOND;
import static br.com.gestorcobranca.shared.security.Permission.CREDITOR_PAYMENT_REPORT;
import static br.com.gestorcobranca.shared.security.Permission.CREDITOR_PORTAL_OVERVIEW;
import static br.com.gestorcobranca.shared.security.Permission.CREDITOR_STATEMENT_RESPOND;

/**
 * Perfil da pessoa do credor no portal do credor (fixo em código — decisão 1396). As permissões são só do conjunto
 * {@link Permission#CREDITOR_ONLY}, resolvidas no servidor a cada requisição (nunca vão no token).
 */
public enum CreditorRole {

    /** Gestão de recuperação: vê tudo e age em contestações e pagamentos; vê a atividade do time. */
    GESTOR("Gestão de recuperação", EnumSet.of(CREDITOR_PORTAL_OVERVIEW, CREDITOR_DISPUTE_RESPOND, CREDITOR_PAYMENT_REPORT,
            CREDITOR_ACTIVITY_VIEW)),
    /** Financeiro: aceita ou contesta a prestação de contas e informa pagamento direto. */
    FINANCEIRO("Financeiro", EnumSet.of(CREDITOR_PORTAL_OVERVIEW, CREDITOR_STATEMENT_RESPOND, CREDITOR_PAYMENT_REPORT)),
    /** Jurídico: responde às contestações que aguardam o credor. */
    JURIDICO("Jurídico", EnumSet.of(CREDITOR_PORTAL_OVERVIEW, CREDITOR_DISPUTE_RESPOND)),
    /** Compliance: acompanha e vê a atividade dos usuários do próprio credor. */
    COMPLIANCE("Compliance", EnumSet.of(CREDITOR_PORTAL_OVERVIEW, CREDITOR_ACTIVITY_VIEW)),
    /** Só consulta. */
    LEITURA("Consulta", EnumSet.of(CREDITOR_PORTAL_OVERVIEW));

    private final String label;
    private final Set<Permission> permissions;

    CreditorRole(String label, Set<Permission> permissions) {
        this.label = label;
        this.permissions = Set.copyOf(permissions);
    }

    public String label() {
        return label;
    }

    public Set<Permission> permissions() {
        return permissions;
    }
}
