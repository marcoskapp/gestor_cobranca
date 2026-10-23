package br.com.gestorcobranca.payment.infrastructure.statement;

import br.com.gestorcobranca.payment.application.port.out.StatementParser;
import br.com.gestorcobranca.payment.domain.model.StatementFormat;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Extrato em CSV genérico ({@code docs/negocio/layout-extrato-conciliacao.md}): UTF-8, separador
 * {@code ;}, cabeçalho obrigatório com as colunas {@code data} e {@code valor} e as opcionais
 * {@code tipo} (C/D, padrão C), {@code id_transacao}, {@code referencia} e {@code descricao}; data
 * {@code AAAA-MM-DD} ou {@code DD/MM/AAAA}; valor com vírgula ou ponto decimal (sem separador de
 * milhar), positivo. Células começadas por {@code = + - @} na descrição são recusadas (fórmulas).
 */
@Component
class GenericCsvStatementParser implements StatementParser {

    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    private static final Pattern AMOUNT = Pattern.compile("\\d{1,13}([.,]\\d{1,2})?");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9\\-_.:/]{1,100}");
    private static final int DESCRIPTION_MAX = 500;

    @Override
    public StatementFormat format() {
        return StatementFormat.CSV_GENERIC;
    }

    @Override
    public List<ParsedLine> parse(InputStream content, int maxLines) {
        Reader reader = new InputStreamReader(content, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
        CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(';').setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true).setTrim(true).setIgnoreHeaderCase(true).get();
        try (CSVParser parser = CSVParser.parse(reader, format)) {
            Map<String, Integer> header = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            header.putAll(parser.getHeaderMap());
            if (!header.containsKey("data") || !header.containsKey("valor")) {
                throw new StatementFormatException("statement.header_invalid", 1,
                        "Cabeçalho deve conter as colunas data e valor");
            }
            List<ParsedLine> lines = new ArrayList<>();
            for (CSVRecord record : parser) {
                int lineNumber = (int) record.getRecordNumber() + 1;
                if (lines.size() >= maxLines) {
                    throw new StatementFormatException("statement.too_many_lines", lineNumber,
                            "Arquivo excede o limite de linhas");
                }
                lines.add(line(record, lineNumber));
            }
            return lines;
        } catch (UncheckedIOException | IOException e) {
            Throwable cause = e instanceof UncheckedIOException u ? u.getCause() : e;
            if (cause instanceof CharacterCodingException) {
                throw new StatementFormatException("statement.encoding_invalid", 0, "Arquivo deve estar em UTF-8");
            }
            throw new StatementFormatException("statement.unreadable", 0, "Arquivo CSV ilegível");
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new StatementFormatException("statement.unreadable", 0, "Arquivo CSV ilegível");
        }
    }

    private static ParsedLine line(CSVRecord record, int lineNumber) {
        LocalDate date = date(value(record, "data"), lineNumber);
        BigDecimal amount = amount(value(record, "valor"), lineNumber);
        String type = value(record, "tipo");
        boolean credit = type == null || type.isBlank() || type.equalsIgnoreCase("C");
        if (!credit && !type.equalsIgnoreCase("D")) {
            throw new StatementFormatException("statement.type_invalid", lineNumber, "Tipo deve ser C ou D");
        }
        String description = value(record, "descricao");
        if (description != null && !description.isEmpty() && "=+-@".indexOf(description.charAt(0)) >= 0) {
            throw new StatementFormatException("statement.formula_rejected", lineNumber,
                    "Descrição não pode começar com fórmula");
        }
        if (description != null && description.length() > DESCRIPTION_MAX) {
            description = description.substring(0, DESCRIPTION_MAX);
        }
        return new ParsedLine(lineNumber, date, amount, credit, identifier(value(record, "id_transacao"), lineNumber),
                identifier(value(record, "referencia"), lineNumber), description == null || description.isBlank()
                ? null : description);
    }

    private static String value(CSVRecord record, String column) {
        return record.isMapped(column) && record.isSet(column) ? record.get(column) : null;
    }

    private static LocalDate date(String value, int lineNumber) {
        if (value == null || value.isBlank()) {
            throw new StatementFormatException("statement.date_required", lineNumber, "Data obrigatória");
        }
        try {
            return value.contains("/") ? LocalDate.parse(value, BR) : LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new StatementFormatException("statement.date_invalid", lineNumber, "Data inválida");
        }
    }

    private static BigDecimal amount(String value, int lineNumber) {
        if (value == null || !AMOUNT.matcher(value).matches()) {
            throw new StatementFormatException("statement.amount_invalid", lineNumber,
                    "Valor inválido (use 1234,56 ou 1234.56)");
        }
        BigDecimal amount = new BigDecimal(value.replace(',', '.'));
        if (amount.signum() <= 0) {
            throw new StatementFormatException("statement.amount_invalid", lineNumber, "Valor deve ser positivo");
        }
        return amount;
    }

    private static String identifier(String value, int lineNumber) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new StatementFormatException("statement.identifier_invalid", lineNumber,
                    "Identificador com caracteres inválidos ou longo demais");
        }
        return value;
    }
}
