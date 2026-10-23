package br.com.gestorcobranca.ingestion.infrastructure.parsing;

import br.com.gestorcobranca.ingestion.domain.model.FileLayout;
import br.com.gestorcobranca.ingestion.domain.model.ImportFileException;
import br.com.gestorcobranca.ingestion.domain.model.SourceRow;
import br.com.gestorcobranca.ingestion.domain.service.ColumnMapping;
import org.apache.poi.EmptyFileException;
import org.apache.poi.UnsupportedFileFormatException;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * XLSX em streaming com a API de eventos (SAX) do Apache POI: a aba é lida linha a linha, sem
 * montar a planilha em memória. O pacote é aberto a partir de arquivo temporário (acesso
 * aleatório ao ZIP, sem descompactar tudo em memória), removido ao final. Somente a tabela de
 * textos compartilhados fica em memória, como exige o formato.
 * <p>
 * O número da linha é o da planilha (primeira linha = 1); linhas em branco são ignoradas. Se o
 * layout tem cabeçalho, ele é a primeira linha não vazia.
 */
final class XlsxRecordReader {

    void read(InputStream content, FileLayout layout, Consumer<SourceRow> consumer) {
        Path temp = null;
        OPCPackage pkg = null;
        try {
            temp = Files.createTempFile("ingestion-", ".xlsx");
            Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING);
            pkg = OPCPackage.open(temp.toFile(), PackageAccess.READ);
            XSSFReader reader = new XSSFReader(pkg);
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg, false);
            StylesTable styles = reader.getStylesTable();
            RowCollector collector = new RowCollector(layout, consumer);
            XMLReader xml = XMLHelper.newXMLReader();
            xml.setContentHandler(new XSSFSheetXMLHandler(styles, strings, collector, new RawValueFormatter(), false));
            try (InputStream sheet = selectSheet(reader, layout.sheetName())) {
                xml.parse(new InputSource(sheet));
            }
            collector.finish();
        } catch (ConsumerFailure e) {
            throw e.original();
        } catch (SAXException e) {
            RuntimeException consumerFailure = ConsumerFailure.find(e);
            if (consumerFailure != null) {
                throw consumerFailure;
            }
            throw unreadable();
        } catch (OpenXML4JException | ParserConfigurationException | UnsupportedFileFormatException | POIXMLException
                 | EmptyFileException e) {
            throw unreadable();
        } catch (IOException e) {
            throw new UncheckedIOException("Falha de leitura do arquivo de importação", e);
        } finally {
            if (pkg != null) {
                // pacote aberto somente para leitura: revert() descarta sem tentar gravar
                pkg.revert();
            }
            deleteTemp(temp);
        }
    }

    private static InputStream selectSheet(XSSFReader reader, String sheetName) throws IOException, OpenXML4JException {
        XSSFReader.SheetIterator sheets = reader.getSheetIterator();
        while (sheets.hasNext()) {
            InputStream sheet = sheets.next();
            if (sheetName == null || sheetName.equals(sheets.getSheetName())) {
                return sheet;
            }
            sheet.close();
        }
        throw sheetName == null
                ? new ImportFileException("import.sheet_not_found", "Planilha sem abas")
                : new ImportFileException("import.sheet_not_found", "Aba não encontrada na planilha: " + sheetName);
    }

    private static ImportFileException unreadable() {
        return new ImportFileException("import.file_unreadable", "Arquivo não é uma planilha XLSX válida");
    }

    private static void deleteTemp(Path temp) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao remover arquivo temporário de importação", e);
        }
    }

    /** Monta as células de cada linha e entrega a linha mapeada ao consumidor. */
    private static final class RowCollector implements XSSFSheetXMLHandler.SheetContentsHandler {

        private final FileLayout layout;
        private final Consumer<SourceRow> consumer;
        private ColumnMapping mapping;
        private List<String> cells = new ArrayList<>();

        RowCollector(FileLayout layout, Consumer<SourceRow> consumer) {
            this.layout = layout;
            this.consumer = consumer;
            this.mapping = layout.hasHeader() ? null : ColumnMapping.resolve(layout, null);
        }

        @Override
        public void startRow(int rowNum) {
            cells = new ArrayList<>();
        }

        @Override
        public void cell(String cellReference, String formattedValue, Comment comment) {
            int column = cellReference == null ? cells.size() : new CellReference(cellReference).getCol();
            while (cells.size() < column) {
                cells.add(null);
            }
            cells.add(formattedValue);
        }

        @Override
        public void endRow(int rowNum) {
            try {
                if (mapping == null) {
                    if (cells.stream().allMatch(c -> c == null || c.isBlank())) {
                        return;
                    }
                    mapping = ColumnMapping.resolve(layout, cells);
                    return;
                }
                SourceRow row = mapping.map(rowNum + 1L, cells);
                if (!row.isBlank()) {
                    consumer.accept(row);
                }
            } catch (RuntimeException e) {
                throw new ConsumerFailure(e);
            }
        }

        void finish() {
            if (mapping == null) {
                throw new ImportFileException("import.header_missing", "Planilha sem linha de cabeçalho");
            }
        }
    }
}
