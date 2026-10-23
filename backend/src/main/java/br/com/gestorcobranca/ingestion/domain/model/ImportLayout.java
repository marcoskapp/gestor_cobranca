package br.com.gestorcobranca.ingestion.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Layout de importação de um credor, versionado: cada publicação cria uma nova versão imutável
 * e desativa a anterior. Lotes registram o layout e a versão usados, o que permite reprocessar
 * um arquivo antigo com o layout com que foi recebido.
 * <p>
 * Invariantes: todos os campos obrigatórios mapeados; cada campo uma única vez; com cabeçalho,
 * cada coluna é identificada por um cabeçalho único (sem diferenciar acentos/maiúsculas); sem
 * cabeçalho, por uma posição única.
 */
@Entity
@Table(name = "import_layout", schema = "ingestion")
public class ImportLayout extends TenantScopedEntity {

    public static final Set<Character> CSV_DELIMITERS = Set.of(';', ',', '|', '\t');
    public static final Set<String> CSV_ENCODINGS = Set.of("UTF-8", "ISO-8859-1", "WINDOWS-1252");
    private static final int CODE_MAX = 40;
    private static final int NAME_MAX = 150;
    private static final int SHEET_MAX = 31;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "code", nullable = false, length = CODE_MAX, updatable = false)
    private String code;

    @Column(name = "layout_version", nullable = false, updatable = false)
    private int layoutVersion;

    @Column(name = "name", nullable = false, length = NAME_MAX, updatable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, length = 10, updatable = false)
    private ImportFormat format;

    @Column(name = "csv_delimiter", length = 1, updatable = false)
    private String csvDelimiter;

    @Column(name = "csv_encoding", length = 20, updatable = false)
    private String csvEncoding;

    @Column(name = "has_header", nullable = false, updatable = false)
    private boolean hasHeader;

    @Column(name = "sheet_name", length = SHEET_MAX, updatable = false)
    private String sheetName;

    @Column(name = "active", nullable = false)
    private boolean active;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "import_layout_column", schema = "ingestion", joinColumns = @JoinColumn(name = "layout_id"))
    private Set<LayoutColumn> columns = new HashSet<>();

    protected ImportLayout() {
    }

    /**
     * @param delimiter delimitador do CSV (; , | ou tabulação); ignorado para XLSX
     * @param encoding  codificação do CSV (UTF-8, ISO-8859-1, WINDOWS-1252); ignorada para XLSX
     */
    public ImportLayout(UUID creditorId, String code, int layoutVersion, String name, ImportFormat format,
                        Character delimiter, String encoding, boolean hasHeader, String sheetName,
                        List<FileLayout.Column> columns) {
        Guard.require(creditorId != null, "import_layout.creditor_required", "Credor obrigatório");
        Guard.require(code != null && code.strip().matches("[A-Za-z0-9_-]{1,40}"), "import_layout.code_invalid",
                "Código do layout deve ter até 40 letras, números, hífen ou sublinhado");
        Guard.require(!FileLayout.STANDARD_CODE.equalsIgnoreCase(code.strip()), "import_layout.code_reserved",
                "Código reservado ao layout padrão");
        Guard.require(layoutVersion > 0, "import_layout.version_invalid", "Versão do layout inválida");
        Guard.require(name != null && !name.isBlank() && name.strip().length() <= NAME_MAX, "import_layout.name_invalid",
                "Nome do layout obrigatório (até 150 caracteres)");
        Guard.require(format == ImportFormat.CSV || format == ImportFormat.XLSX, "import_layout.format_invalid",
                "Formato do layout deve ser CSV ou XLSX");
        this.creditorId = creditorId;
        this.code = code.strip().toUpperCase(Locale.ROOT);
        this.layoutVersion = layoutVersion;
        this.name = name.strip();
        this.format = format;
        this.hasHeader = hasHeader;
        if (format == ImportFormat.CSV) {
            Guard.require(delimiter != null && CSV_DELIMITERS.contains(delimiter), "import_layout.delimiter_invalid",
                    "Delimitador do CSV deve ser ; , | ou tabulação");
            String normalizedEncoding = encoding == null ? null : encoding.strip().toUpperCase(Locale.ROOT);
            Guard.require(normalizedEncoding != null && CSV_ENCODINGS.contains(normalizedEncoding)
                    && isSupported(normalizedEncoding), "import_layout.encoding_invalid",
                    "Codificação do CSV deve ser UTF-8, ISO-8859-1 ou WINDOWS-1252");
            Guard.require(sheetName == null || sheetName.isBlank(), "import_layout.sheet_not_applicable",
                    "Aba só se aplica a XLSX");
            this.csvDelimiter = String.valueOf(delimiter);
            this.csvEncoding = normalizedEncoding;
        } else {
            Guard.require(sheetName == null || sheetName.strip().length() <= SHEET_MAX, "import_layout.sheet_invalid",
                    "Nome da aba deve ter até 31 caracteres");
            this.sheetName = sheetName == null || sheetName.isBlank() ? null : sheetName.strip();
        }
        this.columns = validColumns(columns, hasHeader);
        this.active = true;
    }

    private static boolean isSupported(String encoding) {
        try {
            return Charset.isSupported(encoding);
        } catch (IllegalCharsetNameException e) {
            return false;
        }
    }

    private static Set<LayoutColumn> validColumns(List<FileLayout.Column> columns, boolean hasHeader) {
        Guard.require(columns != null && !columns.isEmpty(), "import_layout.columns_required", "Informe as colunas do layout");
        Set<CanonicalField> fields = EnumSet.noneOf(CanonicalField.class);
        Set<String> headers = new HashSet<>();
        Set<Integer> positions = new HashSet<>();
        Set<LayoutColumn> result = new HashSet<>();
        for (FileLayout.Column column : columns) {
            Guard.require(column.field() != null, "import_layout.field_invalid", "Campo canônico inválido");
            Guard.require(fields.add(column.field()), "import_layout.field_repeated",
                    "Campo %s mapeado mais de uma vez".formatted(column.field().column()));
            if (hasHeader) {
                String header = column.header() == null ? null : column.header().strip();
                Guard.require(header != null && !header.isEmpty() && header.length() <= LayoutColumn.HEADER_MAX
                        && column.position() == null, "import_layout.header_required",
                        "Layout com cabeçalho: informe o cabeçalho (até 120 caracteres) de cada coluna, sem posição");
                Guard.require(headers.add(CanonicalField.normalizeHeader(header)), "import_layout.header_repeated",
                        "Cabeçalho repetido no layout: " + header);
                result.add(new LayoutColumn(column.field(), header, null));
            } else {
                Guard.require(column.position() != null && column.position() > 0 && column.header() == null,
                        "import_layout.position_required",
                        "Layout sem cabeçalho: informe a posição (1, 2, …) de cada coluna, sem cabeçalho");
                Guard.require(positions.add(column.position()), "import_layout.position_repeated",
                        "Posição repetida no layout: " + column.position());
                result.add(new LayoutColumn(column.field(), null, column.position()));
            }
        }
        Set<CanonicalField> missing = EnumSet.allOf(CanonicalField.class).stream()
                .filter(CanonicalField::required).filter(f -> !fields.contains(f))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(CanonicalField.class)));
        Guard.require(missing.isEmpty(), "import_layout.required_fields_missing",
                "Campos obrigatórios não mapeados: " + missing.stream().map(CanonicalField::column)
                        .collect(Collectors.joining(", ")));
        return result;
    }

    /** Retira a versão de uso (substituída por uma nova ou desativada pelo usuário). */
    public void deactivate() {
        this.active = false;
    }

    public FileLayout toFileLayout() {
        List<FileLayout.Column> ordered = columns.stream()
                .map(LayoutColumn::toColumn)
                .sorted(Comparator.comparing(FileLayout.Column::field))
                .toList();
        char delimiter = csvDelimiter == null ? FileLayout.STANDARD_DELIMITER : csvDelimiter.charAt(0);
        return new FileLayout(getId(), code, layoutVersion, format, delimiter, csvEncoding, hasHeader, sheetName, ordered);
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public String getCode() {
        return code;
    }

    public int getLayoutVersion() {
        return layoutVersion;
    }

    public String getName() {
        return name;
    }

    public ImportFormat getFormat() {
        return format;
    }

    public String getCsvDelimiter() {
        return csvDelimiter;
    }

    public String getCsvEncoding() {
        return csvEncoding;
    }

    public boolean isHasHeader() {
        return hasHeader;
    }

    public String getSheetName() {
        return sheetName;
    }

    public boolean isActive() {
        return active;
    }

    public Set<LayoutColumn> getColumns() {
        return Set.copyOf(columns);
    }
}
