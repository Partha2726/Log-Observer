import { expect, test, type Page } from '@playwright/test';
import { ADMIN, DEVOPS, latestOf, login } from './support';

/** Login → search a userId → open the failed trace → red node → evidence → AI overview (fake) with a clickable citation. */
async function coreJourney(page: Page) {
  const failed = latestOf('FAIL_DB_TIMEOUT_APPROVE');
  await login(page, DEVOPS);
  await page.getByRole('link', { name: 'Search' }).click();
  await page.locator('#search-input').fill(failed.requester);
  await page.getByRole('button', { name: 'Search' }).click();
  await expect(page).toHaveURL(new RegExp(`/users/${failed.requester}`));
  await page.getByRole('link', { name: failed.flowRef }).first().click();

  await expect(page.getByRole('heading', { name: failed.flowRef })).toBeVisible();
  const red = page.locator('[data-testid^="graph-node-"]', { hasText: 'first failure' });
  await expect(red).toHaveCount(1);
  await expect(red).toContainText('SQLTimeoutException');
  await red.click();
  await expect(page.getByLabel('Evidence lines').locator('li').first()).toBeVisible();

  const overview = page.locator('[data-ai-state]');
  const regenerate = overview.getByRole('button', { name: /regenerate/i });
  const generate = overview.getByRole('button', { name: /generate overview/i });
  await expect(generate.or(regenerate)).toBeVisible();
  await (await generate.isVisible() ? generate : regenerate).click();
  await expect(overview).toHaveAttribute('data-ai-state', 'completed', { timeout: 60_000 });
  await expect(overview.getByText('AI-generated, verify before acting')).toBeVisible();
  await expect(overview.getByText('Approve:').first()).toBeVisible();
  await overview.getByRole('button', { name: /^Show evidence ev/ }).first().click();
  await expect(page.getByText('Cited evidence')).toBeVisible();
  await expect(page.locator('[data-highlighted="true"]')).toHaveCount(1);
  return failed;
}

test('E2E-001: core journey', async ({ page }) => {
  await coreJourney(page);
});

test('E2E-002: the journey works offline with the fake provider', async ({ page }) => {
  // Block everything that is not the local stack: the UI and the backend must need no outside network.
  const blocked: string[] = [];
  await page.route('**/*', (route) => {
    const host = new URL(route.request().url()).hostname;
    if (host === 'localhost' || host === '127.0.0.1') {
      return route.continue();
    }
    blocked.push(route.request().url());
    return route.abort();
  });
  const failed = await coreJourney(page);
  await page.getByRole('button', { name: 'Ask the assistant' }).click();
  await page.getByRole('button', { name: 'Why did this fail?' }).click();
  const assistant = page.getByLabel('Assistant');
  await expect(assistant.getByText(`Flow ${failed.flowRef} ended FAILED`)).toBeVisible({ timeout: 60_000 });
  await expect(assistant.getByRole('button', { name: /^Show evidence ev/ }).first()).toBeEnabled();
  expect(blocked).toEqual([]);
});

test('E2E-003: dashboard → R01 anomaly → trace → confirm with a note → audit row for the admin', async ({ page }) => {
  await login(page, ADMIN);
  await expect(page.getByRole('table', { name: 'Recent anomalies' })).toContainText('CRITICAL');

  // Dashboard → the R01 count → the anomalies page filtered to R01 (NEW by default; earlier runs may have confirmed some).
  await page.getByLabel('Anomaly counts by rule').getByRole('link', { name: /R01/ }).click();
  await expect(page).toHaveURL(/ruleId=R01/);
  const first = page.getByRole('table', { name: 'Anomalies' }).locator('tbody tr').first();
  const flowRef = (await first.getByRole('link').first().textContent())!.trim();
  await first.getByRole('link', { name: flowRef }).click();
  await expect(page.getByRole('heading', { name: flowRef })).toBeVisible();
  await page.goBack();
  const row = page.locator('tr', { hasText: flowRef });

  const note = `Self-approval checked in E2E ${Date.now()}`;
  await row.getByRole('button', { name: 'Confirm' }).click();
  await row.getByLabel('Review note').fill(note);
  await row.getByRole('button', { name: /save confirmed/i }).click();
  await expect(page.locator('tr', { hasText: flowRef })).toHaveCount(0);

  await page.getByRole('link', { name: 'Admin' }).click();
  await page.getByLabel('Audit action').fill('ANOMALY_REVIEWED');
  await expect(page.getByRole('table', { name: 'Audit events' })).toContainText(note);
});
