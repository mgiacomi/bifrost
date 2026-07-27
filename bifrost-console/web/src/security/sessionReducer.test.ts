import { expect, test } from "vitest";
import { sessionReducer } from "./sessionReducer";

const bootstrap = {
  processId: "process",
  workspacePath: "workspace",
  tabId: "tab",
  csrfToken: "csrf",
};

test("session reducer represents loading paired and unpaired states", () => {
  expect(sessionReducer({ status: "loading" }, { type: "paired", bootstrap })).toEqual({
    status: "paired",
    bootstrap,
  });
  expect(sessionReducer({ status: "paired", bootstrap }, { type: "unpaired", message: "again" })).toEqual({
    status: "unpaired",
    message: "again",
  });
  expect(sessionReducer({ status: "unpaired" }, { type: "loading" })).toEqual({
    status: "loading",
  });
});
