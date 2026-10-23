package br.com.gestorcobranca.ingestion.infrastructure.parsing;

import br.com.gestorcobranca.ingestion.application.port.out.LayoutTemplateWriter;
import br.com.gestorcobranca.ingestion.domain.model.ImportFormat;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Modelo do layout: CSV UTF-8 com BOM (abre direto no Excel) ou XLSX com as colunas formatadas
 * como texto — evita que a planilha converta CPF/CNPJ em número e perca os zeros à esquerda.
 */
@Component
class PoiLayoutTemplateWriter implements LayoutTemplateWriter {

    private static final int TEXT_COLUMN_WIDTH = 22 * 256;

    @Override
    public void write(ImportFormat format, char delimiter, List<String> headers, OutputStream output) {
        try {
            switch (format) {
                case CSV -> csv(delimiter, headers, output);
                case XLSX -> xlsx(headers, output);
                case JSON -> throw new IllegalArgumentException("Modelo disponível apenas em CSV ou XLSX");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao gerar o modelo do layout", e);
        }
    }

    private static void csv(char delimiter, List<String> headers, OutputStream output) throws IOException {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        writer.write('﻿');
        CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setDelimiter(delimiter).get());
        printer.printRecord(headers);
        printer.flush();
    }

    private static void xlsx(List<String> headers, OutputStream output) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("titulos");
            DataFormat dataFormat = workbook.createDataFormat();
            CellStyle text = workbook.createCellStyle();
            text.setDataFormat(dataFormat.getFormat("@"));
            CellStyle header = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            header.setFont(bold);
            header.setDataFormat(dataFormat.getFormat("@"));
            Row row = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                row.createCell(i).setCellValue(headers.get(i));
                row.getCell(i).setCellStyle(header);
                sheet.setDefaultColumnStyle(i, text);
                sheet.setColumnWidth(i, TEXT_COLUMN_WIDTH);
            }
            sheet.createFreezePane(0, 1);
            workbook.write(output);
        }
    }
}
