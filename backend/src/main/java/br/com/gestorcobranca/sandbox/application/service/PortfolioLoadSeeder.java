package br.com.gestorcobranca.sandbox.application.service;

import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseFilter;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.BatchSearch;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ControlTotalsCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportBatchView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportRecordsCommand;
import br.com.gestorcobranca.sandbox.domain.model.DemoPortfolioData.Contract;
import br.com.gestorcobranca.sandbox.domain.model.DemoPortfolioData.Debt;
import br.com.gestorcobranca.sandbox.domain.model.DemoPortfolioData.Debtor;
import br.com.gestorcobranca.sandbox.domain.model.DemoPortfolioData.Title;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Carga das carteiras pela importação via API do módulo ingestion — o mesmo caminho de uma integração de credor:
 * validação do layout, criptografia e blind index dos dados pessoais, lote auditado com totais de controle, devedor
 * casado pelo documento (devedores em mais de um credor) e abertura dos casos de cobrança pelo evento de fim de carga.
 * Feita pelo analista de carteiras. Uma carteira que já tem lote não é importada de novo.
 */
@SandboxSeedComponent
class PortfolioLoadSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(PortfolioLoadSeeder.class);
    private static final Set<String> FINISHED = Set.of("COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED", "CANCELLED");

    private final ImportBatchUseCases batches;
    private final CaseUseCases cases;
    private final SeedRuntime rt;

    PortfolioLoadSeeder(ImportBatchUseCases batches, CaseUseCases cases, SeedRuntime rt) {
        this.batches = batches;
        this.cases = cases;
        this.rt = rt;
    }

    void seed(TenantSeed seed) {
        Actor analyst = seed.actor("analista");
        Map<String, UUID> submitted = new LinkedHashMap<>();
        for (Map.Entry<String, List<Debt>> entry : seed.data.byPortfolio().entrySet()) {
            String code = entry.getKey();
            UUID portfolioId = seed.portfolios.get(code);
            List<ImportBatchView> existing = rt.as(analyst, () -> batches.search(
                    new BatchSearch(null, portfolioId, null, null, null, null), PageQuery.of(0, 5))).content();
            if (!existing.isEmpty()) {
                continue;
            }
            List<Map<String, Object>> records = new ArrayList<>();
            BigDecimal principal = BigDecimal.ZERO;
            for (Debt debt : entry.getValue()) {
                for (Title title : debt.contract().titles()) {
                    records.add(record(debt.debtor(), debt.contract(), title, seed));
                    principal = principal.add(title.principal());
                }
            }
            BigDecimal total = principal;
            String key = "sandbox-" + seed.spec.slug() + "-" + code;
            ImportBatchView batch = rt.as(analyst, () -> batches.importRecords(new ImportRecordsCommand(portfolioId,
                    "IMPORT", false, null, records, null, new ControlTotalsCommand((long) records.size(), total, null,
                    null)), key));
            submitted.put(code, batch.id());
        }
        for (Map.Entry<String, UUID> entry : submitted.entrySet()) {
            UUID batchId = entry.getValue();
            rt.await("importação da carteira " + entry.getKey(), () -> FINISHED.contains(
                    rt.as(analyst, () -> batches.get(batchId)).status()));
            ImportBatchView done = rt.as(analyst, () -> batches.get(batchId));
            if (!"COMPLETED".equals(done.status())) {
                throw new IllegalStateException("importação da carteira " + entry.getKey() + " terminou em "
                        + done.status() + " (" + done.statusReason() + "); veja as ocorrências do lote " + batchId);
            }
            LOG.atInfo().addKeyValue("tenantId", seed.tenantId).addKeyValue("batchId", batchId)
                    .addKeyValue("titles", done.counters().titlesCreated())
                    .log("Carteira do sandbox importada");
        }
        // casos de cobrança abertos pela sincronização (evento de fim de carga → job do collection)
        for (Map.Entry<String, List<Debt>> entry : seed.data.byPortfolio().entrySet()) {
            UUID portfolioId = seed.portfolios.get(entry.getKey());
            long expected = entry.getValue().stream().map(d -> d.debtor().document()).distinct().count();
            rt.await("casos de cobrança da carteira " + entry.getKey(), () -> rt.system(seed.tenantId, () -> cases.search(
                    new CaseFilter(null, portfolioId, null, null, null, null, null, null), PageQuery.of(0, 1))
                    .totalElements()) >= expected);
        }
    }

    /** Linha no layout canônico de importação (docs/negocio/layout-importacao.md). */
    private static Map<String, Object> record(Debtor debtor, Contract contract, Title title, TenantSeed seed) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("documento", debtor.document());
        r.put("nome", debtor.name());
        if (debtor.birthDate() != null) {
            r.put("data_nascimento", debtor.birthDate().toString());
        }
        r.put("telefone_1", debtor.phone());
        r.put("whatsapp_1", debtor.whatsapp() ? "S" : "N");
        if (debtor.email() != null) {
            r.put("email_1", debtor.email());
        }
        r.put("contrato", contract.number());
        r.put("produto", contract.product());
        r.put("data_contrato", contract.signedAt().toString());
        r.put("valor_contrato", contract.amount());
        r.put("id_titulo", title.externalId());
        r.put("parcela", title.installment());
        r.put("vencimento", title.dueDate().toString());
        r.put("valor_principal", title.principal());
        if (contract.instrumentType() != null) {
            r.put("natureza_titulo", contract.instrumentType());
            r.put("titulo_executivo", contract.executive() ? "S" : "N");
        }
        if (contract.vehicle() != null) {
            r.put("garantia_id", contract.number() + "-G1");
            r.put("garantia_tipo", "ALIENACAO_FIDUCIARIA_VEICULO");
            r.put("garantia_bem", "VEICULO");
            r.put("garantia_descricao", contract.vehicle().description());
            r.put("garantia_placa", contract.vehicle().plate());
            r.put("garantia_ano_fabricacao", contract.vehicle().year());
            r.put("garantia_ano_modelo", contract.vehicle().year());
            r.put("garantia_gravame_orgao", "DETRAN-SP");
            r.put("garantia_gravame_numero", "GRV-" + contract.number());
            r.put("garantia_gravame_data", contract.signedAt().toString());
            r.put("avaliacao_valor", contract.vehicle().appraisal());
            r.put("avaliacao_data", seed.today.minusDays(30).toString());
            r.put("avaliacao_fonte", "FIPE");
        }
        return r;
    }
}
