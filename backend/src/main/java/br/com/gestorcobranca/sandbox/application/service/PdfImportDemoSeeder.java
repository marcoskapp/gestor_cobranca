package br.com.gestorcobranca.sandbox.application.service;

import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.PdfImportSearch;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.PdfImportView;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.PdfTemplateView;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.SaveTemplateCommand;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.UpdateMapCommand;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.UploadPdfCommand;
import br.com.gestorcobranca.sandbox.application.SandboxSeedComponent;
import br.com.gestorcobranca.sandbox.application.port.out.DemoPdfRenderer;
import br.com.gestorcobranca.sandbox.application.port.out.DemoPdfShelf;
import br.com.gestorcobranca.sandbox.domain.model.DemoPdf;
import br.com.gestorcobranca.sandbox.domain.model.DemoPdfLayouts;
import br.com.gestorcobranca.sandbox.domain.service.DemoPdfFactory;
import br.com.gestorcobranca.sandbox.domain.service.DemoPdfFactory.DemoPdfSet;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.util.Set;
import java.util.UUID;

/**
 * Importação PDF de demonstração ({@code docs/tecnico/desenho/importacao-pdf.md}), com a IA desligada no sandbox:
 * <ol>
 *   <li>gera os PDFs <strong>fictícios</strong> de demonstração ({@link DemoPdfFactory}) no volume exclusivo do
 *       sandbox, com o índice lido pelo {@code scripts/sandbox/pdfs.sh} — só os que ainda não existem (subir de novo
 *       não troca os arquivos que o responsável já baixou);</li>
 *   <li>salva, pelo caso de uso, o mapa do layout "histórico financeiro" no 1º credor da assessoria, pelo mesmo
 *       caminho de um usuário: envia um PDF modelo do layout, escreve o mapa (sem IA, a importação fica aguardando
 *       mapa), confere que os totais batem, "Salvar mapa para este credor" e descarta o modelo (nada é importado).
 *       Feito pelo analista de carteiras; auditoria e logs de negócio como na operação real.</li>
 * </ol>
 * Idempotente: mapa ativo com o nome {@link DemoPdfLayouts#HISTORY_TEMPLATE_NAME} já existente não é salvo de novo;
 * modelo deixado em aberto por uma carga interrompida é descartado antes de enviar outro.
 */
@SandboxSeedComponent
class PdfImportDemoSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(PdfImportDemoSeeder.class);
    private static final Set<String> MAPPING = Set.of("RECEIVED", "MAPPING");
    private static final String OPEN = "RECEIVED,MAPPING,AWAITING_MAP,AWAITING_VALIDATION";
    static final String MODEL_DISCARD_REASON = "PDF modelo da carga do sandbox: usado só para salvar o mapa do credor";

    private final PdfImportUseCases pdfImports;
    private final DemoPdfRenderer renderer;
    private final DemoPdfShelf shelf;
    private final SeedRuntime rt;

    PdfImportDemoSeeder(PdfImportUseCases pdfImports, DemoPdfRenderer renderer, DemoPdfShelf shelf, SeedRuntime rt) {
        this.pdfImports = pdfImports;
        this.renderer = renderer;
        this.shelf = shelf;
        this.rt = rt;
    }

    void seed(TenantSeed seed) {
        DemoPdfSet set = DemoPdfFactory.forTenant(seed.spec, seed.today);
        publish(seed, set);
        rt.optional("mapa salvo do histórico financeiro", () -> saveTemplate(seed, set));
    }

    private void publish(TenantSeed seed, DemoPdfSet set) {
        int created = 0;
        for (DemoPdf pdf : set.files()) {
            if (!shelf.exists(seed.spec.slug(), pdf.fileName())) {
                shelf.store(seed.spec.slug(), pdf.fileName(), renderer.render(pdf.document()));
                created++;
            }
        }
        shelf.index(seed.spec.slug(), set.files().stream().map(pdf -> new DemoPdfShelf.Entry(seed.spec.slug(),
                set.creditorName(), set.portfolioCode(), pdf.fileName(), pdf.purpose())).toList());
        LOG.atInfo().addKeyValue("tenantId", seed.tenantId).addKeyValue("files", set.files().size())
                .addKeyValue("created", created).log("PDFs fictícios de demonstração da Importação PDF disponíveis");
    }

    private void saveTemplate(TenantSeed seed, DemoPdfSet set) {
        Actor analyst = seed.actor("analista");
        UUID creditorId = seed.creditorId(0);
        boolean saved = rt.as(analyst, () -> pdfImports.templates(creditorId, false)).stream()
                .anyMatch(t -> DemoPdfLayouts.HISTORY_TEMPLATE_NAME.equals(t.name()));
        if (saved) {
            return;
        }
        UUID portfolioId = seed.portfolioId(0, 0);
        String modelName = set.model().fileName();
        rt.as(analyst, () -> pdfImports.search(new PdfImportSearch(creditorId, portfolioId, OPEN), PageQuery.of(0, 50)))
                .content().stream().filter(i -> modelName.equals(i.fileName()))
                .forEach(i -> rt.as(analyst, () -> pdfImports.discard(i.id(), MODEL_DISCARD_REASON)));

        byte[] model = renderer.render(set.model().document());
        UUID id = rt.as(analyst, () -> pdfImports.upload(new UploadPdfCommand(portfolioId, modelName,
                "application/pdf", model.length, () -> new ByteArrayInputStream(model), "SCREEN", null))).id();
        rt.await("leitura do PDF modelo", () -> !MAPPING.contains(rt.as(analyst, () -> pdfImports.get(id)).status()));
        PdfImportView read = rt.as(analyst, () -> pdfImports.get(id));
        if (!"AWAITING_MAP".equals(read.status()) && !"AWAITING_VALIDATION".equals(read.status())) {
            throw new IllegalStateException("PDF modelo do sandbox terminou em " + read.status() + " ("
                    + read.failureCode() + ")");
        }
        // sem IA a importação aguarda o mapa; com o Bedrock ligado, o mapa proposto é trocado pelo da demonstração
        PdfImportView mapped = rt.as(analyst, () -> pdfImports.updateMap(id,
                new UpdateMapCommand(DemoPdfLayouts.historyMap(), read.mapRevision())));
        if (!mapped.mapProblems().isEmpty() || mapped.summary() == null || !mapped.summary().totalsMatch()
                || mapped.summary().records() != countTitles(set.model())) {
            throw new IllegalStateException("mapa de demonstração do histórico não confere com o PDF modelo: problemas="
                    + mapped.mapProblems().size() + ", títulos="
                    + (mapped.summary() == null ? null : mapped.summary().records()));
        }
        PdfTemplateView template = rt.as(analyst, () -> pdfImports.saveTemplate(id,
                new SaveTemplateCommand(DemoPdfLayouts.HISTORY_TEMPLATE_NAME, mapped.mapRevision())));
        rt.as(analyst, () -> pdfImports.discard(id, MODEL_DISCARD_REASON));
        LOG.atInfo().addKeyValue("tenantId", seed.tenantId).addKeyValue("creditorId", creditorId)
                .addKeyValue("templateId", template.id()).addKeyValue("fingerprint", template.fingerprint())
                .log("Mapa salvo de demonstração da Importação PDF criado");
    }

    private static int countTitles(DemoPdf pdf) {
        return pdf.document() instanceof DemoPdf.History h ? h.titles().size() : -1;
    }
}
