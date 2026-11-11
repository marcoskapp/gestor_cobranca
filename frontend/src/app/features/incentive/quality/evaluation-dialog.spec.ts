import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { provideBootstrapDefaults } from '../../../core/config/bootstrap.config';
import { provideBootstrapComponentDefaults } from '../../../core/config/bootstrap-components.config';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { IncentiveAssignees } from '../data/incentive-assignees';
import { QualityApi } from '../data/incentive.api';
import { QualityForm } from '../data/incentive.models';
import type { EvaluationDialogData } from './evaluation-launcher';
import { EvaluationDialog } from './evaluation-list';

const FORM: QualityForm = {
  id: 'f-1',
  name: 'Monitoria de qualidade',
  description: null,
  active: true,
  criteria: [
    { code: 'RESPEITO', description: 'Negociador foi respeitoso', weight: 1, critical: true },
    { code: 'ROTEIRO', description: 'Negociador seguiu roteiro', weight: 2, critical: false },
  ],
};

const PREFILL = {
  userId: '11111111-1111-4111-8111-111111111111',
  userName: 'Bianca Nogueira',
  referenceType: 'CALL' as const,
  referenceId: '22222222-2222-4222-8222-222222222222',
  evaluatedOn: '2026-11-09',
  label: 'chamada feita em 09/11/2026 14:32',
};

describe('EvaluationDialog (registro de monitoria)', () => {
  const close = vi.fn();
  const evaluate = vi.fn();
  let permissions: string[] = [];

  function setup(data: EvaluationDialogData): ComponentFixture<EvaluationDialog> {
    TestBed.configureTestingModule({
      imports: [EvaluationDialog],
      providers: [
        provideAppLocale(),
        provideBootstrapDefaults(),
        provideBootstrapComponentDefaults(),
        provideRouter([]),
        { provide: DIALOG_DATA, useValue: data },
        { provide: DialogRef, useValue: { close } },
        { provide: QualityApi, useValue: { evaluate, evaluableUsers: () => of({ all: true, userIds: [] }) } },
        { provide: IncentiveAssignees, useValue: { source: () => ({ search: () => of([]), resolve: () => of([]) }) } },
        {
          provide: AuthService,
          useValue: {
            user: signal(fakeUser()),
            hasPermission: (p: string) => permissions.includes(p),
            hasAnyPermission: (ps: string[]) => ps.some((p) => permissions.includes(p)),
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(EvaluationDialog);
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => {
    permissions = ['QUALITY_EVALUATE', 'TELEPHONY_SUPERVISE'];
    evaluate.mockReturnValue(of({ id: 'e-1', score: 8 }));
  });

  afterEach(() => vi.clearAllMocks());

  const el = (f: ComponentFixture<EvaluationDialog>) => f.nativeElement as HTMLElement;
  const pick = (f: ComponentFixture<EvaluationDialog>, criterion: number, note: number) => {
    const radios = el(f).querySelectorAll<HTMLInputElement>('[data-testid="evaluation-criterion"]')[criterion].querySelectorAll<HTMLInputElement>('input[type="radio"]');
    radios[note].click();
    f.detectChanges();
  };
  const scoreText = (f: ComponentFixture<EvaluationDialog>) => el(f).querySelector('[data-testid="evaluation-score"]')!.textContent!.replace(/\s+/g, ' ').trim();
  const submit = (f: ComponentFixture<EvaluationDialog>) => {
    el(f).querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    f.detectChanges();
  };

  it('vindo do atendimento: mostra o atendimento fixo, sem campo de identificador, e o formulário único só pelo nome', () => {
    const f = setup({ forms: [FORM], prefill: PREFILL });
    const target = el(f).querySelector('[data-testid="evaluation-prefill"]')!;
    expect(target.textContent).toContain('Bianca Nogueira');
    expect(target.textContent).toContain('Chamada · chamada feita em 09/11/2026 14:32');
    expect(target.textContent).toContain('09/11/2026');
    expect(el(f).querySelector('.font-monospace')).toBeNull();
    expect(el(f).querySelector('select')).toBeNull();
    expect(el(f).querySelector('[data-testid="evaluation-form-name"]')!.textContent).toContain('Monitoria de qualidade');
  });

  it('escala de 0 a 10 por critério, com a nota ponderada e o que falta no rodapé', () => {
    const f = setup({ forms: [FORM], prefill: PREFILL });
    expect(el(f).querySelectorAll('[data-testid="evaluation-criterion"] input[type="radio"]')).toHaveLength(22);
    expect(scoreText(f)).toContain('faltam 2 critérios');
    pick(f, 0, 9);
    expect(scoreText(f)).toContain('falta 1 critério');
    pick(f, 1, 6);
    // (9 × 1 + 6 × 2) / 3 = 7
    expect(scoreText(f)).toMatch(/Nota ponderada\s*7,0\b/);
    expect(el(f).querySelector('[data-testid="evaluation-progress"]')!.textContent).toContain('2 de 2 avaliados');
  });

  it('nota 0 no critério crítico zera a avaliação, destaca o critério e pede comentário', () => {
    const f = setup({ forms: [FORM], prefill: PREFILL });
    pick(f, 0, 0);
    pick(f, 1, 10);
    expect(scoreText(f)).toContain('falha em critério crítico');
    expect(el(f).querySelector('.ev-criterion--critical-fail')).not.toBeNull();
    expect(el(f).textContent).toContain('Descreva o que aconteceu no critério crítico');
    // a regra do crítico não fica só no tooltip: o leitor de tela a lê no legend
    expect(el(f).querySelector('[data-testid="evaluation-criterion"] legend')!.textContent).toContain('nota 0 neste critério zera a avaliação');
  });

  it('registrar sem todas as notas não envia e aponta o critério que falta', () => {
    const f = setup({ forms: [FORM], prefill: PREFILL });
    pick(f, 0, 8);
    submit(f);
    expect(evaluate).not.toHaveBeenCalled();
    expect(el(f).querySelectorAll('.ev-criterion--missing')).toHaveLength(1);
    expect(el(f).querySelector('[role="alert"]')!.textContent).toContain('Dê uma nota a todos os critérios.');
  });

  it('registra com o negociador, a referência e a data do atendimento', () => {
    const f = setup({ forms: [FORM], prefill: PREFILL });
    pick(f, 0, 10);
    pick(f, 1, 7);
    submit(f);
    expect(evaluate).toHaveBeenCalledWith({
      formId: 'f-1',
      userId: PREFILL.userId,
      referenceType: 'CALL',
      referenceId: PREFILL.referenceId,
      evaluatedOn: '2026-11-09',
      scores: { RESPEITO: 10, ROTEIRO: 7 },
      comments: null,
    });
    expect(close).toHaveBeenCalled();
  });

  it('sem atendimento de origem: avaliação geral, sem identificador, com o caminho para avaliar no próprio atendimento', () => {
    const f = setup({ forms: [FORM] });
    expect(el(f).querySelector('[data-testid="evaluation-prefill"]')).toBeNull();
    const type = el(f).querySelector<HTMLSelectElement>('select')!;
    expect(type.options[type.selectedIndex].textContent).toContain('Geral (sem atendimento específico)');
    expect(el(f).querySelector('.font-monospace')).toBeNull();
    const note = el(f).querySelector('[data-testid="evaluation-unlinked"]')!;
    expect(note.textContent).toContain('Chamadas e gravações');
    // a caixa de entrada só aparece para quem a abre
    expect(note.textContent).not.toContain('Caixa de entrada');
  });

  it('avaliação geral registra como OTHER, sem referência, com o negociador escolhido', () => {
    const f = setup({ forms: [FORM] });
    f.componentInstance['form'].controls.user.setValue([PREFILL.userId]);
    pick(f, 0, 10);
    pick(f, 1, 9);
    submit(f);
    expect(evaluate).toHaveBeenCalledWith(expect.objectContaining({ userId: PREFILL.userId, referenceType: 'OTHER', referenceId: null }));
  });
});
