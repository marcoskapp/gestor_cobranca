package br.com.gestorcobranca.publicapi.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.security.Permission;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Escopos de um token de integração. Cada escopo libera um grupo de endpoints da API pública (authority
 * {@code SCOPE_<código>}) e concede ao ator de integração as permissões mínimas exigidas pelos casos de uso dos
 * módulos donos. Nenhum escopo concede {@code DEBTOR_SENSITIVE_VIEW}: a API nunca devolve documento ou contato sem
 * máscara (LGPD, minimização).
 */
public enum TokenScope {

    PORTFOLIOS_READ("portfolios:read", "Consultar o credor, contratos e carteiras",
            EnumSet.of(Permission.CREDITOR_VIEW, Permission.PORTFOLIO_VIEW)),
    PORTFOLIOS_WRITE("portfolios:write", "Criar, atualizar e mudar a situação de carteiras",
            EnumSet.of(Permission.CREDITOR_VIEW, Permission.PORTFOLIO_VIEW, Permission.PORTFOLIO_MANAGE)),
    IMPORTS_READ("imports:read", "Acompanhar lotes de importação e baixar o relatório de ocorrências",
            EnumSet.of(Permission.IMPORT_VIEW)),
    IMPORTS_WRITE("imports:write", "Enviar lotes de importação (JSON, CSV, XLSX) e cancelá-los",
            EnumSet.of(Permission.IMPORT_VIEW, Permission.IMPORT_EXECUTE)),
    TITLES_READ("titles:read", "Consultar títulos, saldos e sincronizar alterações", EnumSet.of(Permission.DEBTOR_VIEW)),
    TITLES_WRITE("titles:write", "Suspender, reativar, cancelar e devolver títulos",
            EnumSet.of(Permission.DEBTOR_VIEW, Permission.DEBT_TITLE_MANAGE)),
    DEBTORS_READ("debtors:read", "Consultar devedores e contatos (sempre mascarados)", EnumSet.of(Permission.DEBTOR_VIEW)),
    DEBTORS_WRITE("debtors:write", "Incluir e invalidar contatos de devedores",
            EnumSet.of(Permission.DEBTOR_VIEW, Permission.DEBTOR_MANAGE)),
    COLLECTION_READ("collection:read", "Consultar a situação da cobrança (casos)", EnumSet.of(Permission.CASE_VIEW)),
    AGREEMENTS_READ("agreements:read", "Consultar acordos e parcelas", EnumSet.of(Permission.AGREEMENT_VIEW)),
    PAYMENTS_READ("payments:read", "Consultar pagamentos", EnumSet.of(Permission.PAYMENT_VIEW)),
    PAYMENTS_WRITE("payments:write", "Informar pagamento recebido diretamente pelo credor",
            EnumSet.of(Permission.PAYMENT_VIEW, Permission.PAYMENT_REGISTER_EXTERNAL)),
    DISPUTES_READ("disputes:read", "Consultar contestações", EnumSet.of(Permission.CASE_VIEW)),
    DISPUTES_WRITE("disputes:write", "Abrir contestações em nome do credor", EnumSet.of(Permission.CASE_VIEW)),
    BILLING_READ("billing:read", "Consultar o consumo do ciclo atual e os extratos da API pública",
            EnumSet.of(Permission.PUBLIC_API_USAGE_VIEW)),
    SETTLEMENT_READ("settlement:read", "Consultar a posição do repasse e os demonstrativos enviados ao credor",
            EnumSet.of(Permission.SETTLEMENT_VIEW));

    private static final Map<String, TokenScope> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(TokenScope::code, Function.identity()));

    private final String code;
    private final String description;
    private final Set<Permission> permissions;

    TokenScope(String code, String description, Set<Permission> permissions) {
        this.code = code;
        this.description = description;
        this.permissions = Set.copyOf(permissions);
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    /** Permissões que o ator de integração recebe com este escopo. */
    public Set<Permission> permissions() {
        return permissions;
    }

    public static TokenScope fromCode(String code) {
        TokenScope scope = code == null ? null : BY_CODE.get(code.strip());
        if (scope == null) {
            throw new BusinessRuleException("integration_token.scope_invalid", "Escopo desconhecido: " + code);
        }
        return scope;
    }

    public static Set<TokenScope> parse(Collection<String> codes) {
        if (codes == null || codes.isEmpty()) {
            throw new BusinessRuleException("integration_token.scope_required", "Informe ao menos um escopo");
        }
        Set<TokenScope> scopes = EnumSet.noneOf(TokenScope.class);
        codes.forEach(c -> scopes.add(fromCode(c)));
        return scopes;
    }

    public static Set<Permission> permissionsOf(Collection<TokenScope> scopes) {
        Set<Permission> all = EnumSet.noneOf(Permission.class);
        scopes.forEach(s -> all.addAll(s.permissions));
        return all;
    }

    public static Set<String> codesOf(Collection<TokenScope> scopes) {
        return scopes.stream().sorted().map(TokenScope::code).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
