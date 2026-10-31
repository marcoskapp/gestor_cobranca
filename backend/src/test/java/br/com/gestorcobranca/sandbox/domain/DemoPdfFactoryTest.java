package br.com.gestorcobranca.sandbox.domain;

import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog;
import br.com.gestorcobranca.sandbox.domain.model.DemoCatalog.TenantSpec;
import br.com.gestorcobranca.sandbox.domain.model.DemoPdf;
import br.com.gestorcobranca.sandbox.domain.service.DemoPdfFactory;
import br.com.gestorcobranca.sandbox.domain.service.DemoPdfFactory.DemoPdfSet;
import br.com.gestorcobranca.shared.domain.Document;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DemoPdfFactoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);

    @Test
    void contentIsFictitiousDeterministicAndConsistent() {
        for (TenantSpec spec : DemoCatalog.tenants()) {
            DemoPdfSet set = DemoPdfFactory.forTenant(spec, TODAY);
            assertThat(set).isEqualTo(DemoPdfFactory.forTenant(spec, TODAY));
            assertThat(set.creditorPrefix()).isEqualTo(spec.creditors().getFirst().prefix());
            assertThat(set.files()).hasSize(5).extracting(DemoPdf::fileName)
                    .allMatch(n -> n.matches("[a-z0-9-]+\\.pdf")).doesNotHaveDuplicates();

            List<String> ids = new ArrayList<>();
            List<DemoPdf> all = new ArrayList<>(set.files());
            all.add(set.model());
            for (DemoPdf pdf : all) {
                if (pdf.document() instanceof DemoPdf.History h) {
                    assertThat(Document.isValid(Document.DocumentType.CNPJ, h.debtor().document().replaceAll("\\D", "")))
                            .isTrue();
                    assertThat(h.debtor().email()).endsWith("@exemplo.test");
                    assertThat(h.titles()).allSatisfy(t -> {
                        assertThat(t.due()).isBefore(TODAY);
                        assertThat(t.pending()).isLessThanOrEqualTo(t.original()).isPositive();
                    });
                    h.titles().forEach(t -> ids.add(t.company() + "-" + t.number() + "-" + t.installment()));
                } else if (pdf.document() instanceof DemoPdf.Table t) {
                    assertThat(t.rows()).isNotEmpty().allSatisfy(r -> {
                        String digits = r.document().replaceAll("\\D", "");
                        assertThat(Document.isValid(digits.length() == 11 ? Document.DocumentType.CPF
                                : Document.DocumentType.CNPJ, digits)).isTrue();
                    });
                }
            }
            Set<String> unique = new HashSet<>(ids);
            assertThat(unique).as("números de título distintos entre os arquivos").hasSize(ids.size());

            DemoPdf.History demo = (DemoPdf.History) set.published().get(0).document();
            DemoPdf.History divergent = (DemoPdf.History) set.published().get(1).document();
            assertThat(demo.totalsConsistent()).isTrue();
            assertThat(divergent.totalsConsistent()).isFalse();
            assertThat(divergent.printedPending().subtract(divergent.sumPending()))
                    .isEqualByComparingTo(DemoPdfFactory.DIVERGENCE);
            assertThat(((DemoPdf.History) set.apiCheck().document()).titles()).hasSize(DemoPdfFactory.API_CHECK_TITLES);
            assertThat(set.published().get(2).document()).isInstanceOf(DemoPdf.Table.class);
            assertThat(((DemoPdf.History) set.published().get(3).document()).titles()).hasSize(DemoPdfFactory.LONG_TITLES);
        }
    }
}
