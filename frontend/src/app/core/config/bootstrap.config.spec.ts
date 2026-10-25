import { ApplicationInitStatus } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { NgbDatepickerConfig, NgbInputDatepickerConfig } from '@ng-bootstrap/ng-bootstrap';
import { IsoDateAdapter, PtBrDateParserFormatter, provideBootstrapDefaults } from './bootstrap.config';
import { NGB_TRANSLATIONS_PT_BR } from './ngb-translations';

describe('padrões do ng-bootstrap', () => {
  const formatter = new PtBrDateParserFormatter();
  const adapter = new IsoDateAdapter();

  it('datas digitadas em dd/MM/aaaa (também ddMMaaaa e d/M/aaaa)', () => {
    expect(formatter.parse('05/03/2026')).toEqual({ year: 2026, month: 3, day: 5 });
    expect(formatter.parse('05032026')).toEqual({ year: 2026, month: 3, day: 5 });
    expect(formatter.parse('5/3/2026')).toEqual({ year: 2026, month: 3, day: 5 });
    expect(formatter.format({ year: 2026, month: 3, day: 5 })).toBe('05/03/2026');
    expect(formatter.format(null)).toBe('');
  });

  it('recusa datas inexistentes e formatos ambíguos', () => {
    expect(formatter.parse('31/02/2026')).toBeNull();
    expect(formatter.parse('2026-03-05')).toBeNull();
    expect(formatter.parse('')).toBeNull();
  });

  it('modelo ISO (LocalDate) sem fuso', () => {
    expect(adapter.fromModel('2026-12-31')).toEqual({ year: 2026, month: 12, day: 31 });
    expect(adapter.toModel({ year: 2026, month: 1, day: 9 })).toBe('2026-01-09');
    expect(adapter.fromModel('31/12/2026')).toBeNull();
    expect(adapter.toModel(null)).toBeNull();
  });

  it('traduz os rótulos acessíveis do ng-bootstrap', () => {
    expect(NGB_TRANSLATIONS_PT_BR['ngb.pagination.next-aria']).toBe('Próxima página');
    expect(NGB_TRANSLATIONS_PT_BR['ngb.toast.close-aria']).toBe('Fechar');
  });

  it('domingo como primeiro dia e faixa 1900–2100 também no datepicker em campo', async () => {
    TestBed.configureTestingModule({ providers: [provideBootstrapDefaults()] });
    await TestBed.inject(ApplicationInitStatus).donePromise;
    for (const config of [TestBed.inject(NgbDatepickerConfig), TestBed.inject(NgbInputDatepickerConfig)]) {
      expect(config.firstDayOfWeek).toBe(7);
      expect(config.minDate).toEqual({ year: 1900, month: 1, day: 1 });
      expect(config.maxDate).toEqual({ year: 2100, month: 12, day: 31 });
    }
  });
});
