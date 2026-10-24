package br.com.gestorcobranca.analytics.web;

import br.com.gestorcobranca.analytics.application.port.in.AnalyticsExportUseCases;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsExportUseCases.ExportFile;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsRefreshUseCases;
import br.com.gestorcobranca.analytics.domain.model.ReportFilter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Vínculo dos filtros da query string (listas repetidas, datas ISO) e cabeçalhos da exportação. */
class AnalyticsControllerTest {

    final AnalyticsQueries queries = mock(AnalyticsQueries.class);
    final AnalyticsExportUseCases exports = mock(AnalyticsExportUseCases.class);
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AnalyticsController(queries, exports,
            mock(AnalyticsRefreshUseCases.class))).build();

    @Test
    void bindsRepeatedListFiltersAndPeriod() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        mvc.perform(get("/api/v1/analytics/recovery").param("from", "2026-06-01").param("to", "2026-06-30")
                        .param("creditorId", a.toString()).param("creditorId", b.toString())
                        .param("channel", "PHONE").param("groupBy", "CREDITOR"))
                .andExpect(status().isOk());
        ArgumentCaptor<ReportFilter> filter = ArgumentCaptor.forClass(ReportFilter.class);
        verify(queries).recovery(filter.capture(), eq("CREDITOR"));
        assertThat(filter.getValue().from()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(filter.getValue().creditorIds()).containsExactlyInAnyOrder(a, b);
        assertThat(filter.getValue().channels()).containsExactly("PHONE");
        assertThat(filter.getValue().operatorIds()).isEmpty();
    }

    @Test
    void exportReturnsAttachmentWithoutCacheAndWithHash() throws Exception {
        when(exports.export(eq("recovery"), any(), any(), eq("CSV"))).thenReturn(new ExportFile("relatorio.csv",
                "text/csv; charset=UTF-8", 3, "f".repeat(64), new ByteArrayInputStream(new byte[]{1, 2, 3})));
        mvc.perform(get("/api/v1/analytics/recovery/export").param("from", "2026-06-01").param("to", "2026-06-30")
                        .param("format", "CSV"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-SHA256", "f".repeat(64)))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("relatorio.csv")));
    }
}
