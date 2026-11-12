import { render, setup, text } from '../../../../testing/component-harness';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { TierSettingsView } from '../data/collection.models';
import { TierSettings } from './tier-settings';

const view: TierSettingsView = {
  capacityPercent: { A: 120, B: 110, C: 100, D: 80 },
  developmentShare: 15,
  defaultTier: 'B',
  customized: false,
  updatedAt: null,
};

const respond = (url: string) => (url.endsWith('/settings/tier-distribution') ? view : undefined);

describe('Parâmetros da distribuição por nível', () => {
  it('avisa que o nível padrão deve coincidir com o da classificação e leva aos parâmetros dela (TIER_MANAGE)', async () => {
    const { http } = setup(TierSettings, ['QUEUE_MANAGE', 'TIER_MANAGE'], 'u-1', [{ provide: ConfirmService, useValue: { confirm: vi.fn() } }]);
    const fixture = await render(TierSettings, http, respond);
    expect(text(fixture)).toContain('Deve ser igual ao nível padrão da Classificação de desempenho');
    const link = (fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>('app-config-link a');
    expect(link).not.toBeNull();
    expect(link!.getAttribute('href')).toBe('/remuneracao/classificacao?aba=parametros');
    expect(link!.getAttribute('target')).toBe('_blank');
  });

  it('sem TIER_MANAGE, a dica continua e o atalho fica oculto', async () => {
    const { http } = setup(TierSettings, ['QUEUE_MANAGE'], 'u-1', [{ provide: ConfirmService, useValue: { confirm: vi.fn() } }]);
    const fixture = await render(TierSettings, http, respond);
    expect(text(fixture)).toContain('Deve ser igual ao nível padrão da Classificação de desempenho');
    expect((fixture.nativeElement as HTMLElement).querySelector('app-config-link a')).toBeNull();
  });
});
