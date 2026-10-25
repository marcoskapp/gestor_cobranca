import { EMPTY } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { render, setup, text } from '../../../../testing/component-harness';
import { FormDialogData, FormDialogService } from '../../legal/components/form-dialog';
import { ruleSet } from '../../../../testing/settlement-fixtures';
import { RuleSetDetail } from './rule-set-detail';

const respond = (view = ruleSet()) => (url: string) => {
  if (url.endsWith('/settlement/rule-sets/rs-1')) return view;
  if (url.endsWith('/settlement/rule-sets')) return [view];
  if (url.endsWith('/contracts')) return [{ id: 'k-1', creditorId: 'c-1', number: 'CT-2026-01', startDate: '2026-01-01', endDate: null, returnAfterDays: null, settlementDay: 1, notes: null, status: 'ACTIVE' }];
  if (url.includes('/creditors/')) return { id: 'c-1', legalName: 'Banco Horizonte S.A.', tradeName: 'Horizonte' };
  if (url.endsWith('/users/display-names')) return [{ id: 'u-2', name: 'Diego Financeiro' }];
  return undefined;
};

const publishButton = (el: HTMLElement) => [...el.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.includes('Publicar'));

describe('RuleSetDetail (regras de comissão)', () => {
  it('quem editou por último não publica: explica e desabilita', async () => {
    const { http } = setup(RuleSetDetail, ['COMMISSION_MANAGE'], 'u-2');
    const fixture = await render(RuleSetDetail, http, respond(), { id: 'rs-1' });
    expect(text(fixture)).toContain('Você editou estas regras por último');
    expect(publishButton(fixture.nativeElement)?.disabled).toBe(true);
    expect(text(fixture)).toContain('PARC_90');
    expect(text(fixture)).toContain('a partir de 91 dias de atraso · parcelado');
  });

  it('outra pessoa com permissão pode publicar', async () => {
    const { http } = setup(RuleSetDetail, ['COMMISSION_MANAGE'], 'u-9');
    const fixture = await render(RuleSetDetail, http, respond(), { id: 'rs-1' });
    expect(publishButton(fixture.nativeElement)?.disabled).toBe(false);
  });

  it('versão publicada é só leitura, com nova versão', async () => {
    const { http } = setup(RuleSetDetail, ['COMMISSION_MANAGE'], 'u-9');
    const fixture = await render(RuleSetDetail, http, respond(ruleSet({ status: 'PUBLISHED', effectiveFrom: '2026-10-01', publishedBy: 'u-9', publishedAt: '2026-09-30T12:00:00Z' })), { id: 'rs-1' });
    expect(text(fixture)).toContain('Nova versão');
    expect(text(fixture)).toContain('a partir de 01/10/2026');
    expect(publishButton(fixture.nativeElement)).toBeUndefined();
  });

  it('publicar pede a data de vigência (a partir do início do contrato) com o resumo do impacto', async () => {
    const { http } = setup(RuleSetDetail, ['COMMISSION_MANAGE'], 'u-9');
    const fixture = await render(RuleSetDetail, http, respond(), { id: 'rs-1' });
    const open = vi.spyOn(TestBed.inject(FormDialogService), 'open').mockReturnValue(EMPTY);
    publishButton(fixture.nativeElement)!.click();
    const data = open.mock.calls[0][0] as FormDialogData;
    expect(data.submitLabel).toBe('Publicar');
    expect(data.details?.map((d) => d.label)).toContain('Versão vigente');
    const field = data.fields[0];
    expect(field).toMatchObject({ key: 'effectiveFrom', type: 'date', required: true });
    expect(field.validate!('2025-12-31')).toContain('01/01/2026');
    expect(field.validate!('2026-01-01')).toBeNull();
  });
});
