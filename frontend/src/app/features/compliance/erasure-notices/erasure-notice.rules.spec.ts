import { latestErasure, localNow, sentAtInstant, sentAtProblem } from './erasure-notice.rules';

describe('erasure-notice.rules', () => {
  const now = new Date('2026-11-26T15:30:40Z');

  it('exige data e recusa futuro e antes da eliminação', () => {
    expect(sentAtProblem('', null, now)).toContain('Informe');
    expect(sentAtProblem('xx', null, now)).toContain('inválidas');
    expect(sentAtProblem(new Date('2026-11-26T15:40:00Z').toISOString(), null, now)).toContain('futura');
    expect(sentAtProblem(new Date('2026-11-26T15:31:00Z').toISOString(), null, now)).toBeNull();
    expect(sentAtProblem(new Date('2026-11-26T15:00:00Z').toISOString(), '2026-11-26T15:20:00Z', now)).toContain('anterior');
  });

  it('no mesmo minuto da eliminação vale e envia o instante da eliminação', () => {
    const typed = new Date('2026-11-26T15:20:00Z').toISOString();
    expect(sentAtProblem(typed, '2026-11-26T15:20:35Z', now)).toBeNull();
    expect(sentAtInstant(typed, '2026-11-26T15:20:35Z')).toBe('2026-11-26T15:20:35.000Z');
    expect(sentAtInstant(new Date('2026-11-26T15:25:00Z').toISOString(), '2026-11-26T15:20:35Z')).toBe('2026-11-26T15:25:00.000Z');
  });

  it('pega a eliminação mais recente e formata agora para o campo', () => {
    expect(latestErasure(['2026-11-20T10:00:00Z', '2026-11-25T10:00:00Z', '2026-11-21T10:00:00Z'])).toBe('2026-11-25T10:00:00Z');
    expect(latestErasure([])).toBeNull();
    expect(localNow(new Date(2026, 10, 26, 9, 5, 59))).toBe('2026-11-26T09:05');
  });
});
