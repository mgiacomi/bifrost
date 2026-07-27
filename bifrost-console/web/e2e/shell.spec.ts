import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { expect, test } from "@playwright/test";

const currentDirectory = path.dirname(fileURLToPath(import.meta.url));
const pom = fs.readFileSync(path.resolve(currentDirectory, "../../..", "pom.xml"), "utf8");
const expectedVersion = pom.match(/<project[\s\S]*?<version>([^<]+)<\/version>/)?.[1];

test("embedded shell serves root, deep link, version, theme, and assets", async ({ page, request }) => {
  const forbidden: string[] = [];
  page.on("request", (current) => {
    if (current.url().includes("/_bifrost/observability/")) forbidden.push(current.url());
  });

  const entryResponse = await page.goto("/");
  await expect(page.getByRole("heading", { name: "Bifrost Console" })).toBeVisible();
  await expect(page.getByTestId("build-version")).toHaveText(expectedVersion ?? "");
  expect(entryResponse?.headers()["cache-control"]).toBe("no-store");

  const scriptSource = await page.locator('script[type="module"]').getAttribute("src");
  expect(scriptSource).toMatch(/assets\/.+-[A-Za-z0-9_-]{8,}\.js$/);
  const assetResponse = await request.get(scriptSource!);
  expect(assetResponse.headers()["cache-control"]).toContain("immutable");

  await page.getByRole("button", { name: /Console theme/ }).focus();
  await page.keyboard.press("Enter");
  await page.keyboard.press("End");
  await page.keyboard.press("Enter");
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");

  await page.goto("/foundation/deep-link");
  await expect(page.getByRole("heading", { name: "Console shell ready" })).toBeVisible();
  expect((await request.get("/assets/missing-12345678.js")).status()).toBe(404);
  expect((await request.get("/api/console/missing")).status()).toBe(404);
  expect(await page.evaluate(() => navigator.serviceWorker?.controller ?? null)).toBeNull();
  expect(forbidden).toEqual([]);
});
