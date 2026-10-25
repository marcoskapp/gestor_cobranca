/**
 * Textos do ng-bootstrap em português (rótulos acessíveis de paginação, datepicker, toasts,
 * alertas e timepicker). O ng-bootstrap usa `$localize` com ids próprios (`@@ngb.*`); as
 * traduções são carregadas em tempo de execução, antes do bootstrap (`main.ts`), com
 * `loadTranslations` de `@angular/localize`. Ids conferidos no pacote instalado
 * (node_modules/@ng-bootstrap/ng-bootstrap/fesm2022); ao atualizar o ng-bootstrap, revise.
 */
export const NGB_TRANSLATIONS_PT_BR: Readonly<Record<string, string>> = {
  'ngb.alert.close': 'Fechar',
  'ngb.toast.close-aria': 'Fechar',
  'ngb.carousel.previous': 'Anterior',
  'ngb.carousel.next': 'Próximo',
  'ngb.datepicker.previous-month': 'Mês anterior',
  'ngb.datepicker.next-month': 'Próximo mês',
  'ngb.datepicker.select-month': 'Selecionar mês',
  'ngb.datepicker.select-year': 'Selecionar ano',
  'ngb.pagination.first-aria': 'Primeira página',
  'ngb.pagination.previous-aria': 'Página anterior',
  'ngb.pagination.next-aria': 'Próxima página',
  'ngb.pagination.last-aria': 'Última página',
  'ngb.timepicker.HH': 'HH',
  'ngb.timepicker.MM': 'MM',
  'ngb.timepicker.SS': 'SS',
  'ngb.timepicker.hours': 'Horas',
  'ngb.timepicker.minutes': 'Minutos',
  'ngb.timepicker.seconds': 'Segundos',
  'ngb.timepicker.increment-hours': 'Aumentar horas',
  'ngb.timepicker.decrement-hours': 'Diminuir horas',
  'ngb.timepicker.increment-minutes': 'Aumentar minutos',
  'ngb.timepicker.decrement-minutes': 'Diminuir minutos',
  'ngb.timepicker.increment-seconds': 'Aumentar segundos',
  'ngb.timepicker.decrement-seconds': 'Diminuir segundos',
};
