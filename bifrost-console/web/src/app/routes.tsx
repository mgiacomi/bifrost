import { createBrowserRouter, createMemoryRouter } from "react-router";
import { App, NotFound } from "./App";
import { Overview } from "../target/Overview";
import { buildMetadata, type BuildMetadata } from "./metadata";

function definitions(metadata: BuildMetadata) {
  return [
    {
      path: "/",
      element: <App metadata={metadata} />,
      children: [
        { index: true, element: <Overview /> },
        { path: "*", element: <NotFound /> },
      ],
    },
  ];
}

export function browserRouter() {
  return createBrowserRouter(definitions(buildMetadata));
}

export function memoryRouter(path: string, metadata: BuildMetadata) {
  return createMemoryRouter(definitions(metadata), { initialEntries: [path] });
}
