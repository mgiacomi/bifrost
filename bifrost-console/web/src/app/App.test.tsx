import { render, screen } from "@testing-library/react";
import { RouterProvider } from "react-router";
import { expect, test } from "vitest";
import { memoryRouter } from "./routes";

const metadata = { version: "0.1.0-SNAPSHOT" };

test("renders the console shell and exact build version", () => {
  render(<RouterProvider router={memoryRouter("/", metadata)} />);
  expect(screen.getByRole("heading", { name: "Bifrost Console" })).toBeVisible();
  expect(screen.getByRole("heading", { name: "Console shell ready" })).toBeVisible();
  expect(screen.getByTestId("build-version")).toHaveTextContent("0.1.0-SNAPSHOT");
});

test("renders the foundation at a deep route", () => {
  render(<RouterProvider router={memoryRouter("/foundation/deep-link", metadata)} />);
  expect(screen.getByRole("heading", { name: "Console shell ready" })).toBeVisible();
});

test("renders a safe not-found route as text", () => {
  const unsafe = `<img src=x onerror=alert("unsafe")>`;
  render(<RouterProvider router={memoryRouter(`/${encodeURIComponent(unsafe)}`, metadata)} />);
  expect(screen.getByRole("heading", { name: "This Console route does not exist" })).toBeVisible();
  expect(document.querySelector("img")).toBeNull();
});
