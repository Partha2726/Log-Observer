/**
 * Thin wrapper around where the JWT access token is persisted.
 *
 * This is a real application (not a Claude Artifact), so `localStorage`
 * is fine here — it is what lets a page refresh keep the user logged in
 * without a backend-side session. Isolated behind this module so the
 * storage mechanism can be swapped later (e.g. for an httpOnly-cookie
 * based refresh flow) without touching every call site.
 */
const STORAGE_KEY = 'ielop.auth.token';

export function getStoredToken(): string | null {
  try {
    return window.localStorage.getItem(STORAGE_KEY);
  } catch {
    // Storage can throw in some privacy modes/environments; treat as
    // "no token" rather than crashing the app.
    return null;
  }
}

export function setStoredToken(token: string): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, token);
  } catch {
    // Ignore — worst case the user is prompted to log in again.
  }
}

export function clearStoredToken(): void {
  try {
    window.localStorage.removeItem(STORAGE_KEY);
  } catch {
    // Ignore.
  }
}
