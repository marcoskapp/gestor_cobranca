/**
 * Custos operacionais e margem por credor e carteira (fase 1) — docs/tecnico/desenho/margem-por-carteira.md.
 * <p>
 * Guarda os cadastros de custo (faixas de custo de pessoas, faixa de cada usuário, tarifas de canal, câmbio da IA e
 * custos fixos com a regra de rateio), apura por job diário os fatos de custo por dia × credor × carteira × categoria
 * ({@code costing.cost_daily}) e responde o painel e a exportação de margem.
 * <p>
 * Não chama casos de uso dos módulos de origem do uso (collection, telephony, communication, assistant, ingestion,
 * incentive, legal, settlement, negotiation): lê o banco por views próprias do schema {@code costing}
 * ({@code security_invoker}, RLS da assessoria preservado), com o contrato de colunas em
 * {@code db/migration/costing/R__costing_contract.sql}. Do identity usa só o diretório de usuários (validar a pessoa
 * de uma faixa e listar nomes); do organization, a existência do credor de um custo fixo direto.
 */
@ApplicationModule(displayName = "Costing (margem por credor e carteira)",
        allowedDependencies = {"shared", "organization :: usecases", "identity :: usecases"})
package br.com.gestorcobranca.costing;

import org.springframework.modulith.ApplicationModule;
