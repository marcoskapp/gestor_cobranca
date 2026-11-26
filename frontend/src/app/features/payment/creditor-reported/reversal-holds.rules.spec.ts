import { HOLD_ERROR_LABELS, HOLD_RELEASE_REASON_LABELS, holdAge, holdOverdue } from './reversal-holds.rules';

describe('reversal-holds.rules', () => {
  const now = new Date('2026-11-26T15:00:00Z');

  it('idade da pendência em dias inteiros', () => {
    expect(holdAge('2026-11-26T10:00:00Z', now)).toBe('hoje');
    expect(holdAge('2026-11-25T10:00:00Z', now)).toBe('há 1 dia');
    expect(holdAge('2026-11-16T10:00:00Z', now)).toBe('há 10 dias');
  });

  it('pendência antiga ganha destaque depois de 5 dias (nunca é liberada sozinha)', () => {
    expect(holdOverdue('2026-11-22T15:00:00Z', now)).toBe(false);
    expect(holdOverdue('2026-11-20T15:00:00Z', now)).toBe(true);
  });

  it('textos sem código interno', () => {
    for (const text of [...Object.values(HOLD_RELEASE_REASON_LABELS), ...Object.values(HOLD_ERROR_LABELS)]) {
      expect(text).not.toMatch(/[A-Z]{3,}_|reversal_hold/);
    }
  });
});
