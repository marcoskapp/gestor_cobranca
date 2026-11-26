package br.com.gestorcobranca.ingestion.domain.model;

import br.com.gestorcobranca.ingestion.domain.model.SubjectBlock.Line;
import br.com.gestorcobranca.ingestion.domain.model.SubjectBlock.MatchedBy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubjectBlockTest {

    @Test
    void foundInTheLoadHasNoEventAndTheAnonymizationAlwaysHasOne() {
        UUID batch = UUID.randomUUID();
        UUID creditor = UUID.randomUUID();
        SubjectBlock load = SubjectBlock.foundInLoad(batch, creditor, null, List.of(new Line(2, "R-7", MatchedBy.TITLE_REFERENCE)));
        assertThat(load.getEffect()).isEqualTo(BlockEffect.FOUND_IN_LOAD);
        assertThat(load.getSourceEventId()).isNull();
        assertThatThrownBy(() -> new SubjectBlock(batch, creditor, null, null, BlockEffect.ALREADY_FINISHED, null, List.of()))
                .hasFieldOrPropertyWithValue("code", "import_subject_block.origin_invalid");
        assertThatThrownBy(() -> new SubjectBlock(batch, creditor, null, UUID.randomUUID(), BlockEffect.FOUND_IN_LOAD, null,
                List.of())).hasFieldOrPropertyWithValue("code", "import_subject_block.origin_invalid");
    }

    @Test
    void keepsAtMostTheLineLimitButCountsAll() {
        List<Line> many = IntStream.rangeClosed(1, SubjectBlock.MAX_LINES + 3)
                .mapToObj(i -> new Line(i + 1, "R-" + i, MatchedBy.DEBTOR)).toList();
        SubjectBlock block = SubjectBlock.foundInLoad(UUID.randomUUID(), UUID.randomUUID(), null, many);
        assertThat(block.getLines()).isEqualTo(SubjectBlock.MAX_LINES + 3);
        assertThat(block.getLineList()).hasSize(SubjectBlock.MAX_LINES);
    }

    @Test
    void displayReferenceMasksEmbeddedDocumentAndControlCharacters() {
        assertThat(SubjectBlock.displayReference("R-52998224725-001")).doesNotContain("52998224725");
        assertThat(SubjectBlock.displayReference("cobranca_52998224725\n.csv")).doesNotContain("52998224725")
                .doesNotContain("\n");
        assertThat(SubjectBlock.displayReference(" \t")).isEqualTo("(sem referência)");
        assertThat(SubjectBlock.displayReference(null)).isEqualTo("(sem referência)");
    }
}
