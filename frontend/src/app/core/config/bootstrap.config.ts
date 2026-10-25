import { EnvironmentProviders, Injectable, inject, makeEnvironmentProviders, provideAppInitializer } from '@angular/core';
import {
  NgbDateAdapter,
  NgbDateParserFormatter,
  NgbDateStruct,
  NgbModalConfig,
} from '@ng-bootstrap/ng-bootstrap';

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
 * Padrões do ng-bootstrap fornecidos na raiz: datas pt-BR com modelo ISO e diálogos acessíveis
 * (Esc fecha, foco devolvido). Ficam aqui só o que a moldura inicial usa (diálogos e avisos); os
 * padrões dos demais componentes estão em `bootstrap-components.config.ts`, arquivo separado para
 * que o código desses componentes fique fora do bundle inicial.
 * Os rótulos acessíveis do ng-bootstrap são traduzidos em `ngb-translations.ts`.
 */
export function provideBootstrapDefaults(): EnvironmentProviders {
  return makeEnvironmentProviders([
    { provide: NgbDateAdapter, useClass: IsoDateAdapter },
    { provide: NgbDateParserFormatter, useClass: PtBrDateParserFormatter },
    provideAppInitializer(() => {
      const modal = inject(NgbModalConfig);
      modal.backdrop = true;
      modal.keyboard = true;
      modal.scrollable = true;
    }),
  ]);
}
