package br.com.gestorcobranca.negotiation.domain.model.authority;

import br.com.gestorcobranca.negotiation.domain.model.authority.AuthorityProfile.Parameters;

import java.math.BigDecimal;
import java.util.List;

/**
 * Perfis de alçada semeados em toda assessoria (§4), com parâmetros usuais de mercado e
 * ajustáveis depois por quem tem {@code AUTHORITY_MANAGE}. O nível 99 significa "até o topo da
 * escada da política" (o limite efetivo é sempre o mínimo com a política).
 *
 * <pre>
 * perfil              nível  desconto  parcelas  valor máx.   entrada  formaliza aprova reneg.
 * JUNIOR                1      50%        6      R$ 5.000      10%       sim      não     0
 * PLENO                 2      70%       12      R$ 20.000     10%       sim      não     1
 * SENIOR                3      85%       18      R$ 50.000      5%       sim      não     1
 * SUPERVISOR           99     100%       24      R$ 200.000     0%       sim      sim     2
 * GESTOR               99     100%      360      sem limite     0%       sim      sim     3
 * ASSISTENTE_INICIAL    1      50%        6      R$ 3.000      10%       não      não     0
 * </pre>
 * O {@code ASSISTENTE_INICIAL} é o perfil conservador de partida do assistente de IA: não
 * formaliza sozinho (o acordo depende de humano — modo {@code AUTONOMOUS_WITH_APPROVAL}) até que a
 * operação valide o desempenho e ajuste a alçada.
 */
public final class StandardAuthorityProfiles {

    public static final String JUNIOR = "JUNIOR";
    public static final String PLENO = "PLENO";
    public static final String SENIOR = "SENIOR";
    public static final String SUPERVISOR = "SUPERVISOR";
    public static final String GESTOR = "GESTOR";
    public static final String ASSISTENTE_INICIAL = "ASSISTENTE_INICIAL";

    /** Perfil aplicado a usuário sem atribuição. */
    public static final String DEFAULT_FOR_USERS = JUNIOR;
    /** Perfil aplicado a política de assistente sem atribuição. */
    public static final String DEFAULT_FOR_ASSISTANT = ASSISTENTE_INICIAL;

    public record Definition(String code, Parameters parameters) {
    }

    private StandardAuthorityProfiles() {
    }

    public static List<Definition> all() {
        return List.of(
                def(JUNIOR, "Negociador júnior", "Primeiros níveis da escada, parcelamento curto", 1, "50", 6, "5000",
                        "10", true, false, 0),
                def(PLENO, "Negociador pleno", "Escada intermediária", 2, "70", 12, "20000", "10", true, false, 1),
                def(SENIOR, "Negociador sênior", "Escada completa com parte do envelope", 3, "85", 18, "50000", "5",
                        true, false, 1),
                def(SUPERVISOR, "Supervisor", "Envelope completo; aprova propostas da equipe", AuthorityProfile.MAX_LEVEL,
                        "100", 24, "200000", "0", true, true, 2),
                def(GESTOR, "Gestor", "Envelope completo sem limite próprio de valor", AuthorityProfile.MAX_LEVEL, "100",
                        360, null, "0", true, true, 3),
                def(ASSISTENTE_INICIAL, "Assistente de IA (inicial)",
                        "Alçada conservadora do assistente; formalização depende de humano", 1, "50", 6, "3000", "10",
                        false, false, 0));
    }

    private static Definition def(String code, String name, String description, int level, String share, int installments,
                                  String maxAmount, String downShare, boolean formalize, boolean approve, int reneg) {
        return new Definition(code, new Parameters(name, description, level, new BigDecimal(share), installments,
                maxAmount == null ? null : new BigDecimal(maxAmount), new BigDecimal(downShare), formalize, approve, reneg,
                List.of()));
    }
}
