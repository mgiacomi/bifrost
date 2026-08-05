import { createBrowserRouter, createMemoryRouter } from "react-router";
import { App, NotFound } from "./App";
import { Overview } from "../target/Overview";
import { ObservabilityOverview } from "../observability/Overview";
import { SkillCatalog } from "../observability/SkillCatalog";
import { SkillDetailView } from "../observability/SkillDetail";
import { ActiveExecutions } from "../observability/ActiveExecutions";
import { ActiveExecutionDetailView } from "../observability/ActiveExecutionDetail";
import { Traces } from "../observability/Traces";
import { TraceDetailView } from "../observability/TraceDetail";
import { TraceStorage } from "../observability/TraceStorage";
import { buildMetadata, type BuildMetadata } from "./metadata";

function definitions(metadata: BuildMetadata) {
  return [
    {
      path: "/",
      element: <App metadata={metadata} />,
      children: [
        { index: true, element: <ObservabilityOverview /> },
        { path: "target", element: <Overview /> },
        { path: "skills", element: <SkillCatalog /> },
        { path: "skills/:registeredName", element: <SkillDetailView /> },
        { path: "active-executions", element: <ActiveExecutions /> },
        { path: "active-executions/:sessionId", element: <ActiveExecutionDetailView /> },
        { path: "traces", element: <Traces /> },
        { path: "traces/:traceId", element: <TraceDetailView /> },
        { path: "trace-storage", element: <TraceStorage /> },
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
