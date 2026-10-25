import { EnvironmentProviders, Injectable, inject, makeEnvironmentProviders, provideEnvironmentInitializer } from '@angular/core';
import { NgbDateAdapter, NgbDateParserFormatter, NgbDateStruct, NgbDatepickerConfig, NgbInputDatepickerConfig } from '@ng-bootstrap/ng-bootstrap';

const pad = (value: number, size = 2) => String(value).padStart(size, '0');

/**
 * Modelo das datas nos formulários = `LocalDate` do backend em ISO (`yyyy-MM-dd`), sem fuso
 * (nunca `Date`, que desloca o dia conforme o fuso do navegador).
 */
@Injectable()
export class IsoDateAdapter extends NgbDateAdapter<string> {
  fromModel(value: string | null): NgbDateStruct | null {
    const match = value ? /^(\d{4})-(\d{2})-(\d{2})$/.exec(value) : null;
    return match ? { year: Number(match[1]), month: Number(match[2]), day: Number(match[3]) } : null;
  }

  toModel(date: NgbDateStruct | null): string | null {
    return date ? `${pad(date.year, 4)}-${pad(date.month)}-${pad(date.day)}` : null;
  }
}

/** Digitação e exibição em `dd/MM/aaaa` (aceita também `ddMMaaaa` e `d/M/aaaa`). */
@Injectable()
export class PtBrDateParserFormatter extends NgbDateParserFormatter {
  parse(value: string): NgbDateStruct | null {
    const text = value?.trim() ?? '';
    const match = /^(\d{1,2})\/(\d{1,2})\/(\d{4})$/.exec(text) ?? /^(\d{2})(\d{2})(\d{4})$/.exec(text);
    if (!match) {
      return null;
    }
    const [day, month, year] = [Number(match[1]), Number(match[2]), Number(match[3])];
    const probe = new Date(Date.UTC(year, month - 1, day));
    const valid = probe.getUTCFullYear() === year && probe.getUTCMonth() === month - 1 && probe.getUTCDate() === day;
    return valid ? { year, month, day } : null;
  }

  format(date: NgbDateStruct | null): string {
    return date ? `${pad(date.day)}/${pad(date.month)}/${pad(date.year, 4)}` : '';
  }
}

/**
 * Padrões do datepicker do ng-bootstrap: modelo ISO (`IsoDateAdapter`), digitação/exibição
 * pt-BR (`PtBrDateParserFormatter`), domingo como primeiro dia, intervalo 1900–2100 e calendário
 * anexado ao `body` (não é cortado pela rolagem de diálogos e painéis, como os menus e tooltips).
 *
 * Fornecido no injetor das rotas do shell e do portal — e não na raiz — porque o esbuild divide o
 * código por arquivo: referenciar o módulo do datepicker na raiz levava o datepicker inteiro (e o
 * `@angular/forms`, do qual ele depende) para o bundle inicial assim que as telas passaram a usá-lo.
 * Em testes de telas com datepicker, inclua `provideDatepickerDefaults()` (ou `provideBootstrapDefaults()`).
 */
export function provideDatepickerDefaults(): EnvironmentProviders {
  return makeEnvironmentProviders([
    { provide: NgbDateAdapter, useClass: IsoDateAdapter },
    { provide: NgbDateParserFormatter, useClass: PtBrDateParserFormatter },
    provideEnvironmentInitializer(() => {
      // o calendário embutido (NgbDatepicker) e o do campo (NgbInputDatepicker) têm configurações
      // separadas no ng-bootstrap: os mesmos padrões valem para os dois
      for (const config of [inject(NgbDatepickerConfig), inject(NgbInputDatepickerConfig)]) {
        config.firstDayOfWeek = 7; // domingo, como no calendário brasileiro
        config.minDate = { year: 1900, month: 1, day: 1 };
        config.maxDate = { year: 2100, month: 12, day: 31 };
      }
      inject(NgbInputDatepickerConfig).container = 'body';
    }),
  ]);
}
