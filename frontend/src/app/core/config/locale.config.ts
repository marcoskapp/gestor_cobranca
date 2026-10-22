import { DATE_PIPE_DEFAULT_OPTIONS, registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import {
  DEFAULT_CURRENCY_CODE,
  EnvironmentProviders,
  LOCALE_ID,
  makeEnvironmentProviders,
} from '@angular/core';
import { provideDateFnsAdapter } from '@angular/material-date-fns-adapter';
import { MAT_DATE_LOCALE, MatDateFormats } from '@angular/material/core';
import { ptBR } from 'date-fns/locale';

export const APP_LOCALE = 'pt-BR';

registerLocaleData(localePt, APP_LOCALE);

const DATE_FORMATS: MatDateFormats = {
  parse: { dateInput: ['dd/MM/yyyy', 'ddMMyyyy', 'd/M/yyyy'] },
  display: {
    dateInput: 'dd/MM/yyyy',
    monthYearLabel: 'MMM yyyy',
    dateA11yLabel: 'PPP',
    monthYearA11yLabel: 'MMMM yyyy',
  },
};

/** Locale pt-BR: datas dd/MM/yyyy, moeda BRL e datepicker em português. */
export function provideLocale(): EnvironmentProviders {
  return makeEnvironmentProviders([
    { provide: LOCALE_ID, useValue: APP_LOCALE },
    { provide: DEFAULT_CURRENCY_CODE, useValue: 'BRL' },
    { provide: DATE_PIPE_DEFAULT_OPTIONS, useValue: { dateFormat: 'dd/MM/yyyy' } },
    { provide: MAT_DATE_LOCALE, useValue: ptBR },
    provideDateFnsAdapter(DATE_FORMATS),
  ]);
}
