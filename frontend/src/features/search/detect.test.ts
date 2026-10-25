import { describe, expect, it, vi } from 'vitest';
import { detect } from '@/features/search/detect';
import definitions from '@/test/contract/definitions.json';
import type { FlowDefinition } from '@/api/schemas';

const defs = definitions as FlowDefinition[];
const known = vi.fn(async (id: string) => id === 'usr-1042');

/** UI-005: each input shape of spec section 12.4 goes to its page. */
describe('search auto-detection', () => {
  it.each([
    ['REQ-20260515-000944', 'flowRef', '/traces/REQ-20260515-000944'],
    ['0c12301b-e4a1-41e6-8933-897567896a4f', 'requestId', '/requests/0c12301b-e4a1-41e6-8933-897567896a4f'],
    ['usr-1042', 'userId', '/users/usr-1042'],
    ['sess_3fa9c01b77e24d10', 'sessionId', '/logs?q=sess_3fa9c01b77e24d10'],
    ['10.20.4.17', 'clientIp', '/logs?clientIp=10.20.4.17'],
    ['2001:db8::17', 'clientIp', '/logs?clientIp=2001%3Adb8%3A%3A17'],
    ['pp-7f3a09c1d2e4', 'externalId', '/logs?q=pp-7f3a09c1d2e4'],
    ['usr-9999', 'text', '/logs?q=usr-9999'],
    ['Limit lookup failed', 'text', '/logs?q=Limit+lookup+failed'],
  ])('%s is a %s', async (input, kind, to) => {
    const d = await detect(input, defs, known);
    expect(d.kind).toBe(kind);
    expect(d.to).toBe(to);
  });

  it('only asks whether a user exists for inputs that could be a user ID', async () => {
    known.mockClear();
    await detect('REQ-20260515-000944', defs, known);
    await detect('10.20.4.17', defs, known);
    await detect('two words', defs, known);
    expect(known).not.toHaveBeenCalled();
  });

  it('uses the refPattern of every loaded flow definition', async () => {
    const tickets = { ...defs[0], id: 'tickets', refPattern: '^TKT-\\d{6}$' };
    expect((await detect('TKT-000123', [...defs, tickets], known)).kind).toBe('flowRef');
  });
});
