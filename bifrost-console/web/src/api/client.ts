import type {
  BootstrapResponse,
  BrowserErrorCode,
  ErrorEnvelope,
  PairingLinkResponse,
  TargetResponse,
  BrowserErrorDetails,
  InstanceStatus,
  SkillSummary,
  SkillDetail,
  ActiveExecution,
  ActivePage,
  Trace,
  Page,
  Activity,
  ActivityKind,
  RecentActivityResponse,
  RecentActivityRequest,
  ConnectionFact,
  Continuity,
} from "./contracts";
import { openActivityStream as openStream } from "./activityStream";

export class BrowserAPIError extends Error {
  constructor(
    readonly code: BrowserErrorCode,
    message: string,
    readonly status: number,
    readonly targetScopeId?: string,
    readonly details?: BrowserErrorDetails,
  ) {
    super(message);
  }
}

type SecurityState = {
  tabId: string;
  csrfToken: string;
};

async function post<T>(
  path: string,
  body: object,
  security?: SecurityState,
  keepalive = false,
): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (security) {
    headers["X-Bifrost-Console-Tab"] = security.tabId;
    headers["X-Bifrost-Console-CSRF"] = security.csrfToken;
  }
  const response = await fetch(path, {
    method: "POST",
    credentials: "same-origin",
    headers,
    body: JSON.stringify(body),
    cache: "no-store",
    redirect: "error",
    keepalive,
  });
  const parsed = (await response.json()) as T | ErrorEnvelope;
  if (!response.ok) {
    const error = (parsed as ErrorEnvelope).error;
    throw new BrowserAPIError(
      error.code,
      error.message,
      response.status,
      error.targetScopeId,
      error.details,
    );
  }
  return parsed as T;
}

export async function exchangePairing(secret: string): Promise<void> {
  await post<{ paired: boolean }>("/api/console/v1/pairing/exchange", { secret });
}

export function requestManualPairing(): Promise<{ challengePrinted: boolean }> {
  return post("/api/console/v1/pairing/challenge", {});
}

export function bootstrap(tabId?: string): Promise<BootstrapResponse> {
  return post("/api/console/v1/bootstrap", tabId ? { tabId } : {});
}

export function createPairingLink(security: SecurityState): Promise<PairingLinkResponse> {
  return post("/api/console/v1/pairing/link", {}, security);
}

export function releaseTab(security: SecurityState): Promise<{ released: boolean }> {
  return post("/api/console/v1/tabs/release", {}, security, true);
}

export function heartbeatTab(security: SecurityState): Promise<{ active: boolean }> {
  return post("/api/console/v1/tabs/heartbeat", {}, security);
}

export function targetStatus(): Promise<TargetResponse> {
  return post("/api/console/v1/target/status", {});
}

export function connectTarget(
  targetAddress: string,
  applicationKey: string,
  security: SecurityState,
): Promise<TargetResponse> {
  return post("/api/console/v1/target/connect", { targetAddress, applicationKey }, security);
}

export function supplyTargetCredential(
  applicationKey: string,
  security: SecurityState,
): Promise<TargetResponse> {
  return post("/api/console/v1/target/credential", { applicationKey }, security);
}

export function recheckTarget(security: SecurityState): Promise<TargetResponse> {
  return post("/api/console/v1/target/recheck", {}, security);
}

export function getObservabilityInstance(): Promise<InstanceStatus> {
  return post("/api/console/v1/observability/instance", {});
}

export function listSkills(
  cursor?: string,
  pageSize?: number,
): Promise<Page<SkillSummary>> {
  return post("/api/console/v1/skills/list", { cursor: cursor ?? "", pageSize: pageSize ?? 0 });
}

export function getSkillDetail(registeredName: string): Promise<SkillDetail> {
  return post("/api/console/v1/skills/detail", { registeredName });
}

export function listActiveExecutions(
  cursor?: string,
  pageSize?: number,
): Promise<ActivePage> {
  return post("/api/console/v1/active-executions/list", { cursor: cursor ?? "", pageSize: pageSize ?? 0 });
}

export function getActiveExecutionDetail(sessionId: string): Promise<ActiveExecution> {
  return post("/api/console/v1/active-executions/detail", { sessionId });
}

export function listTraces(
  cursor?: string,
  pageSize?: number,
): Promise<Page<Trace>> {
  return post("/api/console/v1/traces/list", { cursor: cursor ?? "", pageSize: pageSize ?? 0 });
}

export function getTraceDetail(traceId: string): Promise<Trace> {
  return post("/api/console/v1/traces/detail", { traceId });
}

export function fetchRecentActivities(
  request?: RecentActivityRequest,
  security?: SecurityState,
): Promise<RecentActivityResponse> {
  return post("/api/console/v1/activity/recent", {
    cursor: request?.cursor ?? "",
    sessionId: request?.sessionId ?? "",
    limit: request?.limit ?? 0,
  }, security);
}

export type ActivityStreamCallbacks = {
  onActivity: (activity: Activity) => void;
  onConnection?: (fact: ConnectionFact) => void;
  onContinuity?: (continuity: Continuity) => void;
  onReplayGap?: (reason: string) => void;
  onBaselineRefreshed?: (observedAt?: string) => void;
  onError?: (error: Error) => void;
  onClose?: () => void;
};

export function openActivityStream(
  callbacks: ActivityStreamCallbacks,
  security: SecurityState,
  afterCursor?: string,
): () => void {
  return openStream(
    {
      url: "/api/console/v1/activity/stream",
      body: { afterCursor: afterCursor ?? "" },
      tabId: security.tabId,
      csrfToken: security.csrfToken,
    },
    callbacks,
  );
}
