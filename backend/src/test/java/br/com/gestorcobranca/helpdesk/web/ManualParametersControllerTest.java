package br.com.gestorcobranca.helpdesk.web;

import br.com.gestorcobranca.helpdesk.application.port.in.ManualParameterUseCases;
import br.com.gestorcobranca.helpdesk.application.port.in.ManualParameterUseCases.ManualParametersView;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contrato HTTP dos parâmetros do manual: só os valores, pela chave, e {@code no-store}. */
class ManualParametersControllerTest {

    private final ManualParameterUseCases parameters = mock(ManualParameterUseCases.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ManualParametersController(parameters)).build();

    @Test
    void returnsTheValuesByKeyWithoutCache() throws Exception {
        when(parameters.current()).thenReturn(new ManualParametersView(Map.of("debt.cumulativeWindowDays",
                BigDecimal.valueOf(30), "scoring.minAuc", new BigDecimal("0.60"))));
        mvc.perform(get("/api/v1/helpdesk/manual-parameters"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.values['debt.cumulativeWindowDays']").value(30))
                .andExpect(jsonPath("$.values['scoring.minAuc']").value(0.60));
    }
}
