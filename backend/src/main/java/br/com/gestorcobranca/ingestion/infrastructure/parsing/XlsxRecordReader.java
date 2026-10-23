package br.com.gestorcobranca.ingestion.infrastructure.parsing;

import br.com.gestorcobranca.ingestion.application.port.out.ImportRecordReader.RecordSink;
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
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * XLSX em streaming com a API de eventos (SAX) do Apache POI: a aba é lida linha a linha, sem
 * montar a planilha em memória. O pacote é aberto a partir de arquivo temporário (acesso
 * aleatório ao ZIP, sem descompactar tudo em memória), removido ao final. Somente a tabela de
 * textos compartilhados fica em memória, como exige o formato.
 * <p>
 * O número da linha é o da planilha (primeira linha = 1); linhas em branco são ignoradas. Se o
 * layout tem cabeçalho, ele é a primeira linha não vazia.
 * <p>
 * Proteção contra zip bomb em duas camadas: antes de abrir, o índice do ZIP é inspecionado
 * (quantidade de partes, tamanhos declarados, tabela de textos, razão de compressão); durante a
 * leitura, o {@code ZipSecureFile} do POI (configurado em {@code infrastructure.config.IngestionParsingConfig}) confere a
 * descompactação real, que não depende do que o arquivo declara. Fórmulas não são avaliadas: o
 * leitor entrega o último valor calculado gravado na planilha.
 */
final class XlsxRecordReader {

    private static final String SHARED_STRINGS = "xl/sharedStrings.xml";

    private final XlsxLimits limits;

    XlsxRecordReader(XlsxLimits limits) {
        this.limits = limits;
    }

    /** Limites do pacote XLSX. */
    record XlsxLimits(long maxUncompressedTotal, long maxSharedStrings, int maxEntries, int maxCompressionRatio) {
    }

    void read(InputStream content, FileLayout layout, RecordSink sink) {
        Path temp = null;
        OPCPackage pkg = null;
        try {
            try {
                temp = Files.createTempFile("ingestion-", ".xlsx");
                Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // armazenamento ou disco local: transitória
                throw new UncheckedIOException("Falha de leitura do arquivo de importação", e);
            }
            inspect(temp);
            pkg = OPCPackage.open(temp.toFile(), PackageAccess.READ);
            XSSFReader reader = new XSSFReader(pkg);
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg, false);
            StylesTable styles = reader.getStylesTable();
            RowCollector collector = new RowCollector(layout, sink);
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
            // o arquivo já está em disco local: falha de leitura aqui é do conteúdo (ZIP corrompido ou
            // descompactação recusada pelo ZipSecureFile), permanente
            throw new ImportFileException("import.file_unreadable",
                    "Arquivo não é uma planilha XLSX válida ou foi recusado por segurança");
        } finally {
            if (pkg != null) {
                // pacote aberto somente para leitura: revert() descarta sem tentar gravar
                pkg.revert();
            }
            deleteTemp(temp);
        }
    }

    /** Recusa pacotes cujo índice já denuncia descompactação desproporcional. */
    private void inspect(Path file) throws IOException {
        long total = 0;
        int entries = 0;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                if (++entries > limits.maxEntries()) {
                    throw suspicious("partes demais no pacote");
                }
                long size = entry.getSize();
                long compressed = entry.getCompressedSize();
                if (size < 0) {
                    continue;
                }
                total += size;
                if (total > limits.maxUncompressedTotal()) {
                    throw suspicious("conteúdo descompactado excede o limite");
                }
                if (SHARED_STRINGS.equals(entry.getName()) && size > limits.maxSharedStrings()) {
                    throw suspicious("tabela de textos excede o limite");
                }
                if (compressed > 0 && size > 1024 * 1024 && size / compressed > limits.maxCompressionRatio()) {
                    throw suspicious("razão de compressão suspeita");
                }
            }
        } catch (ZipException e) {
            throw unreadable();
        }
    }

    private static ImportFileException suspicious(String detail) {
        return new ImportFileException("import.file_rejected", "Planilha recusada por segurança: " + detail);
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
        private final RecordSink sink;
        private ColumnMapping mapping;
        private List<String> cells = new ArrayList<>();

        RowCollector(FileLayout layout, RecordSink sink) {
            this.layout = layout;
            this.sink = sink;
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
                    sink.ignoredColumns(mapping.ignoredColumns());
                    return;
                }
                SourceRow row = mapping.map(rowNum + 1L, cells);
                if (!row.isBlank()) {
                    sink.row(row);
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
