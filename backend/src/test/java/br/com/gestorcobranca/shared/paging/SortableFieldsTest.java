package br.com.gestorcobranca.shared.paging;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SortableFieldsTest {

    private static final Sort DEFAULT = Sort.by(Sort.Direction.DESC, "openedAt").and(Sort.by("id"));
    private static final SortableFields FIELDS = SortableFields.builder()
            .field("openBalance", "openBalance")
            .inverted("daysOverdue", "oldestDueDate")
            .build();

    @Test
    void semPedidoUsaAOrdemPadrao() {
        assertThat(FIELDS.resolve(null, DEFAULT)).isEqualTo(DEFAULT);
        assertThat(PageQuery.of(0, 20, null).sort()).isNull();
        assertThat(PageQuery.of(0, 20, "  ").sort()).isNull();
    }

    @Test
    void campoDaListaBrancaOrdenaPelaPropriedadeComDesempatePorId() {
        Sort sort = FIELDS.resolve(SortRequest.parse("openBalance,desc"), DEFAULT);
        assertThat(sort).isEqualTo(Sort.by(Sort.Order.desc("openBalance")).and(Sort.by("id")));
        assertThat(FIELDS.resolve(SortRequest.parse("openBalance"), DEFAULT))
                .isEqualTo(Sort.by(Sort.Order.asc("openBalance")).and(Sort.by("id")));
    }

    @Test
    void campoInvertidoTrocaADirecao() {
        assertThat(FIELDS.resolve(SortRequest.parse("daysOverdue,desc"), DEFAULT))
                .isEqualTo(Sort.by(Sort.Order.asc("oldestDueDate")).and(Sort.by("id")));
        assertThat(FIELDS.resolve(SortRequest.parse("daysOverdue,ASC"), DEFAULT))
                .isEqualTo(Sort.by(Sort.Order.desc("oldestDueDate")).and(Sort.by("id")));
    }

    @Test
    void campoForaDaListaBrancaERecusado() {
        // propriedade real da entidade, mas não publicada: nunca chega ao banco
        assertThatThrownBy(() -> FIELDS.resolve(SortRequest.parse("oldestDueDate"), DEFAULT))
                .isInstanceOf(InvalidSortException.class);
        assertThatThrownBy(() -> PageQuery.of(0, 20, "document,asc").toPageable(FIELDS, DEFAULT))
                .isInstanceOf(InvalidSortException.class);
    }

    @Test
    void formatoInvalidoERecusado() {
        for (String raw : new String[] {"a.b", "name,up", "name,asc,desc", "1name", "name;drop", "debtor.name,asc",
                "x".repeat(41)}) {
            assertThatThrownBy(() -> SortRequest.parse(raw)).as(raw).isInstanceOf(InvalidSortException.class);
        }
    }

    @Test
    void nomeRepetidoNaListaBrancaEErroDeProgramacao() {
        assertThatThrownBy(() -> SortableFields.builder().field("a", "a").field("a", "b"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
