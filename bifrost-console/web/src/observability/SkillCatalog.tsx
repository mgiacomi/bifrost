import { useEffect, useRef } from "react";
import { Link } from "react-router";
import { useObservability } from "./ObservabilityProvider";
import type { SkillSummary } from "../api/contracts";
import { scopeBoundPath } from "./scope";

export function SkillCatalog() {
  const { skills, loadSkills } = useObservability();
  const heading = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    heading.current?.focus();
  }, []);

  useEffect(() => {
    if (!skills.loaded && !skills.loading && !skills.error) {
      void loadSkills();
    }
  }, [skills, loadSkills]);

  return (
    <section aria-labelledby="skill-catalog-title" className="overview-card">
      <p className="eyebrow">Operational views</p>
      <h2 id="skill-catalog-title" ref={heading} tabIndex={-1}>Skill Catalog</h2>
      <button type="button" disabled={skills.loading} onClick={() => void loadSkills()}>
        Refresh
      </button>

      {skills.error && (
        <div className="target-error" role="alert">
          <strong>{skills.error.message}</strong>
          <div>
            <button type="button" disabled={skills.loading} onClick={() => void loadSkills()}>
              Retry
            </button>
          </div>
        </div>
      )}

      {skills.loading && <p>Loading skills…</p>}

      {skills.loaded && !skills.loading && skills.items.length === 0 && !skills.error && (
        <p>No skills are registered.</p>
      )}

      {skills.items.length > 0 && (
        <div className="observability-table-region" role="region" aria-label="Skill catalog table" tabIndex={0}>
          <table className="observability-table">
          <thead>
            <tr>
              <th scope="col">Registered name</th>
              <th scope="col">Source path</th>
            </tr>
          </thead>
          <tbody>
            {skills.items.map((skill) => {
              const s = skill as SkillSummary;
              return (
                <tr key={s.registeredName}>
                  <td>
                    <Link to={scopeBoundPath(`/skills/${encodeURIComponent(s.registeredName)}`, skills.targetScopeId)}>{s.registeredName}</Link>
                  </td>
                  <td><code className="source-path">{s.sourcePath}</code></td>
                </tr>
              );
            })}
          </tbody>
          </table>
        </div>
      )}

      {skills.hasMore && skills.nextCursor && (
        <button type="button" disabled={skills.loading} onClick={() => void loadSkills(skills.nextCursor ?? undefined)}>
          Load more
        </button>
      )}
    </section>
  );
}
