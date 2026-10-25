import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, type Page } from '@playwright/test';

/** Ground truth of the generated demo dataset (data/ground-truth/flows.jsonl); generated data only. */
export interface TruthFlow {
  flowRef: string;
  scenario: string;
  requester: string;
  approver: string | null;
  overallStatus: string;
  expectedFailingStage: string | null;
}

export function flows(): TruthFlow[] {
  const file = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', 'data', 'ground-truth', 'flows.jsonl');
  return readFileSync(file, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l) as TruthFlow);
}

/** The latest flow of a scenario, so it lies inside the UI's default "last 7 days" range. */
export function latestOf(scenario: string): TruthFlow {
  const f = flows().filter((x) => x.scenario === scenario).pop();
  if (!f) {
    throw new Error(`No ${scenario} flow in data/ground-truth/flows.jsonl; run make demo first`);
  }
  return f;
}

/** The dev profile's seeded users (section 14; dev only). */
export const DEVOPS = { username: 'devops.dev', password: 'DevPassword123!' };
export const ADMIN = { username: 'admin.dev', password: 'DevPassword123!' };

export async function login(page: Page, user: { username: string; password: string }) {
  await page.goto('/login');
  await page.locator('#login-username').fill(user.username);
  await page.locator('#login-password').fill(user.password);
  await page.getByRole('button', { name: 'Log in' }).click();
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
}
