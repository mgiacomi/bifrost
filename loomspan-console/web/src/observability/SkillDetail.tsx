import { useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router";
import { BrowserAPIError, getSkillDetail } from "../api/client";
import type { SkillDetail } from "../api/contracts";
import { useTarget } from "../target/TargetProvider";
import {
  recoverObservabilityError,
  requireCurrentTargetScope,
} from "./scope";
import { useScopeBoundRoute } from "./useScopeBoundRoute";

export function SkillDetailView() {
  const { registeredName } = useParams();
  const { target, scopeGeneration, refresh } = useTarget();
  const [detail, setDetail] = useState<SkillDetail | null>(null);
  const [error, setError] = useState<BrowserAPIError | null>(null);
  const [loading, setLoading] = useState(true);
  const heading = useRef<HTMLHeadingElement>(null);
  const refreshTarget = useRef(refresh);
  refreshTarget.current = refresh;
  const routeIsCurrent = useScopeBoundRoute();

  useEffect(() => {
    heading.current?.focus();
  }, []);

  useEffect(() => {
    if (!registeredName || !routeIsCurrent) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    setDetail(null);
    getSkillDetail(registeredName)
      .then(async (d) => {
        await requireCurrentTargetScope(d.targetScopeId, target.status.targetScopeId, refreshTarget.current);
        if (!cancelled) setDetail(d);
      })
      .catch(async (err) => {
        const recovered = await recoverObservabilityError(err, refreshTarget.current);
        if (cancelled) return;
        setError(recovered);
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [registeredName, routeIsCurrent, scopeGeneration, target.status.targetScopeId]);

  return (
    <section aria-labelledby="skill-detail-title" className="overview-card">
      <p className="eyebrow">Operational views</p>
      <h2 id="skill-detail-title" ref={heading} tabIndex={-1}>Skill Detail</h2>

      <p>
        <Link to="/skills">Back to Skill Catalog</Link>
      </p>

      {error && (
        <div className="target-error" role="alert">
          <strong>{error.message}</strong>
        </div>
      )}

      {loading && <p>Loading skill detail…</p>}

      {detail && (
        <>
          <dl className="status-grid">
            <div>
              <dt>Registered name</dt>
              <dd>{detail.registeredName}</dd>
            </div>
            <div>
              <dt>Source path</dt>
              <dd><code className="source-path">{detail.sourcePath}</code></dd>
            </div>
          </dl>

          <h3>Skill YAML</h3>
          <pre className="yaml-block" aria-label="Skill YAML source">{detail.yaml}</pre>
        </>
      )}
    </section>
  );
}
