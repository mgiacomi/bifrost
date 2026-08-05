export type TraceExplorerView = "hierarchy" | "timeline" | "usage" | "records";

const views = new Set<TraceExplorerView>(["hierarchy", "timeline", "usage", "records"]);

// Explorer coordinates are conveniences for a current target scope only. This
// parser deliberately drops malformed selections rather than applying them to
// an unrelated trace after a navigation or refresh.
export function readTraceExplorerState(params: URLSearchParams) {
  const view = params.get("view");
  const recordSequence = params.get("recordSequence");
  return {
    view: views.has(view as TraceExplorerView) ? view as TraceExplorerView : "hierarchy" as TraceExplorerView,
    frameId: params.get("frameId") || undefined,
    failureId: params.get("failureId") || undefined,
    recordSequence: recordSequence && /^[1-9]\d*$/.test(recordSequence) ? Number(recordSequence) : undefined,
    valid: (!view || views.has(view as TraceExplorerView)) && (!recordSequence || /^[1-9]\d*$/.test(recordSequence)),
  };
}

export function setTraceExplorerSelection(params: URLSearchParams, values: Record<string, string | number | undefined>) {
  const next = new URLSearchParams(params);
  for (const [key, value] of Object.entries(values)) {
    if (value === undefined) next.delete(key); else next.set(key, String(value));
  }
  return next;
}
