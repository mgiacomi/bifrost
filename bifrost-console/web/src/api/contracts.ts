export type BrowserErrorCode =
  | "INVALID_REQUEST"
  | "BROWSER_SECURITY_REJECTED"
  | "PAIRING_REJECTED"
  | "SESSION_REQUIRED"
  | "LIMIT_EXCEEDED"
  | "RATE_LIMITED"
  | "PAIRING_UNAVAILABLE"
  | "METHOD_NOT_ALLOWED"
  | "NOT_FOUND"
  | "INVALID_ARGUMENT"
  | "TARGET_AUTHENTICATION_REQUIRED"
  | "TARGET_ACCESS_BLOCKED"
  | "TARGET_UNAVAILABLE"
  | "INCOMPATIBLE_TARGET"
  | "TARGET_CHANGED"
  | "INVALID_CURSOR"
  | "STALE_CURSOR"
  | "ARTIFACT_EXPIRED"
  | "INVALID_ARTIFACT"
  | "LIVE_MONITORING_UNAVAILABLE"
  | "LOCAL_STORAGE_UNAVAILABLE"
  | "CONSOLE_ERROR";

export type TargetStatus = {
  observedAt: string;
  targetScopeId?: string;
  targetSelection: "NONE" | "SELECTED";
  targetConnection: "NOT_APPLICABLE" | "UNKNOWN" | "REACHABLE" | "UNAVAILABLE";
  targetAuthentication:
    | "NOT_APPLICABLE"
    | "UNKNOWN"
    | "REQUIRED"
    | "ESTABLISHED"
    | "BLOCKED";
  javaGoCompatibility:
    | "NOT_APPLICABLE"
    | "NOT_CHECKED"
    | "COMPATIBLE"
    | "INCOMPATIBLE";
  runtimeIdentity: "NOT_APPLICABLE" | "NOT_ESTABLISHED" | "ESTABLISHED";
  instanceId?: string;
  liveMonitoring: "NOT_APPLICABLE" | "UNKNOWN" | "AVAILABLE" | "UNAVAILABLE";
};

export type TargetResponse = {
  address?: string;
  unencrypted: boolean;
  status: TargetStatus;
};

export type BrowserErrorDetails = {
  expectedCompatibilityVersion?: string;
  observedCompatibilityVersion?: string;
  currentTargetScopeId?: string;
  transportCategory?: string;
  limitName?: string;
  limitValue?: number;
  rawDownloadAvailable?: boolean;
};

export type ErrorEnvelope = {
  error: {
    code: BrowserErrorCode;
    message: string;
    targetScopeId?: string;
    details?: BrowserErrorDetails;
  };
};

export type BootstrapResponse = {
  processId: string;
  workspacePath: string;
  tabId: string;
  csrfToken: string;
  target: TargetResponse;
};

export type PairingLinkResponse = {
  pairingUrl: string;
};
