import type { TraceFrame, TraceUsage as Usage, TraceUsageValue } from "../api/contracts";

function UsageRows({ rows }: { rows: Array<[string, TraceUsageValue | undefined, boolean | undefined]> }) {
  return <tbody>{rows.map(([kind, value, complete]) => <tr key={kind}><th scope="row">{kind}{complete === false && <span> (incomplete)</span>}</th><td>{value?.promptUnits ?? "unavailable"}</td><td>{value?.completionUnits ?? "unavailable"}</td><td>{value?.totalUnits ?? "unavailable"}</td></tr>)}</tbody>;
}

export function TraceUsage({ usage, frame }: { usage?: Usage; frame?: TraceFrame }) {
  if (!usage) return <p role="status">Loading usage…</p>;
  const rows: Array<[string, TraceUsageValue | undefined, boolean | undefined]> = frame ? [
    ["Selected frame direct", frame.directUsage, frame.directUsageComplete],
    ["Selected frame descendants", frame.descendantUsage, frame.descendantUsageComplete],
    ["Selected frame inclusive", frame.inclusiveUsage, frame.inclusiveUsageComplete],
  ] : [
    ["Attributed", usage.attributed, undefined], ["Unattributed", usage.unattributed, undefined],
    ["Unframed attributed", usage.unframedAttributed, undefined], ["Terminal", usage.terminal, undefined],
  ];
  return <table aria-label="Usage facts"><caption>{frame ? `Usage for ${frame.route || frame.frameId}` : "Trace usage breakdown"}</caption><thead><tr><th>Kind</th><th>Prompt</th><th>Completion</th><th>Total</th></tr></thead><UsageRows rows={rows} /></table>;
}
