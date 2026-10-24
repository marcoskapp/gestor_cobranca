import { contrastWithWhite } from './portal-admin.api';

describe('contraste da marca do portal', () => {
  it('segue a fórmula WCAG (igual à do servidor)', () => {
    expect(contrastWithWhite('#000000')).toBeCloseTo(21, 1);
    expect(contrastWithWhite('#1D4E89')!).toBeGreaterThan(4.5);
    expect(contrastWithWhite('#FFD700')!).toBeLessThan(3);
    expect(contrastWithWhite('azul')).toBeNull();
  });
});
