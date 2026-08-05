import type { TraceRange } from "../api/contracts";

export function TraceEvidenceDetail({ range, pending = false, onNext, onClear }: { range?: TraceRange; pending?: boolean; onNext: () => void; onClear: () => void }) {
  if (!range) return null;
  return <section aria-label="Evidence content"><p>{range.encoding === "BASE64" ? "Base64-encoded" : "Text"} bytes {range.actualStart}–{range.actualEnd} of {range.totalLength} ({range.contentType})</p><pre>{range.content}</pre>{range.hasMore && range.nextCursor && <button type="button" disabled={pending} onClick={onNext}>{pending ? "Reading…" : "Read next range"}</button>}<button type="button" onClick={onClear}>Clear content</button></section>;
}
