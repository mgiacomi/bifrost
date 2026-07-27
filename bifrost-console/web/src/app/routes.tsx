import { createBrowserRouter, createMemoryRouter } from "react-router";
import { App, Foundation, NotFound } from "./App";
import { buildMetadata, type BuildMetadata } from "./metadata";

function definitions(metadata: BuildMetadata) {
  return [
    {
      path: "/",
      element: <App metadata={metadata} />,
      children: [
        { index: true, element: <Foundation /> },
        { path: "foundation/deep-link", element: <Foundation /> },
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
