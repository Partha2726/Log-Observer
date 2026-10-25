import type { FlowDefinition } from '@/api/schemas';

/**
 * Search-box auto-detection (spec section 12.4). Flow references come from the loaded
 * flow definitions; the other ID shapes are the ones section 12.4 lists.
 */
export type Detected =
  | { kind: 'flowRef'; value: string; to: string }
  | { kind: 'requestId'; value: string; to: string }
  | { kind: 'userId'; value: string; to: string }
  | { kind: 'sessionId'; value: string; to: string }
  | { kind: 'clientIp'; value: string; to: string }
  | { kind: 'externalId'; value: string; to: string }
  | { kind: 'text'; value: string; to: string };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const SESSION = /^sess_[0-9a-f]{16}$/;
const EXTERNAL_ID = /^pp-[0-9a-f]{12}$/;
const IPV4 = /^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$/;
const IPV6 = /^[0-9a-f:]+$/i;
/** Default of app.ids.user-pattern on the backend. */
export const USER_PATTERN = /^[A-Za-z0-9._@-]{1,64}$/;

function isIpv6(v: string): boolean {
  return v.includes(':') && IPV6.test(v) && v.split(':').length >= 3;
}

function logs(params: Record<string, string>): string {
  return `/logs?${new URLSearchParams(params).toString()}`;
}

/**
 * Classifies the input. {@code userExists} answers "does this user ID appear in the logs?"
 * and is called only for inputs that could be a user ID.
 */
export async function detect(raw: string, definitions: FlowDefinition[], userExists: (id: string) => Promise<boolean>): Promise<Detected> {
  const value = raw.trim();
  for (const def of definitions) {
    if (new RegExp(def.refPattern).test(value)) {
      return { kind: 'flowRef', value, to: `/traces/${encodeURIComponent(value)}` };
    }
  }
  if (UUID.test(value)) {
    return { kind: 'requestId', value, to: `/requests/${value.toLowerCase()}` };
  }
  if (SESSION.test(value)) {
    return { kind: 'sessionId', value, to: logs({ q: value }) };
  }
  if (IPV4.test(value) || isIpv6(value)) {
    return { kind: 'clientIp', value, to: logs({ clientIp: value }) };
  }
  if (EXTERNAL_ID.test(value)) {
    return { kind: 'externalId', value, to: logs({ q: value }) };
  }
  if (USER_PATTERN.test(value) && (await userExists(value))) {
    return { kind: 'userId', value, to: `/users/${encodeURIComponent(value)}` };
  }
  return { kind: 'text', value, to: logs({ q: value }) };
}
