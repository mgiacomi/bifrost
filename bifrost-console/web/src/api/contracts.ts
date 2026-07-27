export type BrowserErrorCode =
  | "INVALID_REQUEST"
  | "BROWSER_SECURITY_REJECTED"
  | "PAIRING_REJECTED"
  | "SESSION_REQUIRED"
  | "LIMIT_EXCEEDED"
  | "RATE_LIMITED"
  | "PAIRING_UNAVAILABLE"
  | "METHOD_NOT_ALLOWED"
  | "NOT_FOUND";

export type ErrorEnvelope = {
  error: {
    code: BrowserErrorCode;
    message: string;
  };
};

export type BootstrapResponse = {
  processId: string;
  workspacePath: string;
  tabId: string;
  csrfToken: string;
};

export type PairingLinkResponse = {
  pairingUrl: string;
};
