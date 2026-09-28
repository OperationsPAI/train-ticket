import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  intakeChannels,
  platformChannels,
  readIntakeSheet,
  type IntakeSheet,
} from "./intake-channels.js";

const routes: Record<string, string> = {
  APP: "app-desk",
  WEB: "web-desk",
  PHONE: "phone-desk",
  IM: "chat-desk",
  EMAIL: "mail-desk",
  IN_APP_MESSAGE: "message-desk",
  BOT: "bot-desk",
  OPERATOR_CONSOLE: "console-desk",
};

function sheet(staffedQueues?: readonly string[], ownRoutes: Record<string, string> = routes): IntakeSheet {
  return staffedQueues === undefined ? { routes: ownRoutes } : { routes: ownRoutes, staffedQueues };
}

describe("the intake's channel vocabulary", () => {
  it("answers the channels whose queue the sheet staffs", () => {
    assert.deepEqual(intakeChannels(sheet(["web-desk", "mail-desk"])), ["WEB", "EMAIL"]);
    assert.deepEqual(intakeChannels(sheet([...Object.values(routes)])), [...platformChannels]);
  });

  it("keeps the platform's order whichever way the queues are written", () => {
    assert.deepEqual(intakeChannels(sheet(["message-desk", "web-desk", "phone-desk"])), ["WEB", "PHONE", "IN_APP_MESSAGE"]);
  });

  it("drops a channel the sheet routes to a queue it does not staff", () => {
    assert.deepEqual(intakeChannels(sheet(["web-desk"], { WEB: "web-desk", APP: "mobile-desk" })), ["WEB"]);
  });

  it("drops a channel the sheet does not route", () => {
    assert.deepEqual(intakeChannels(sheet(["app-desk", "web-desk"], { WEB: "web-desk" })), ["WEB"]);
  });

  it("leaves the whole vocabulary when the sheet states no queue", () => {
    assert.deepEqual(intakeChannels(sheet(undefined)), [...platformChannels]);
    assert.deepEqual(intakeChannels(sheet([])), [...platformChannels]);
    assert.deepEqual(intakeChannels({}), [...platformChannels]);
  });

  it("leaves the whole vocabulary when there is no sheet at all", () => {
    const absent = readIntakeSheet("/nonexistent/intake-channels.json");
    assert.deepEqual(intakeChannels(absent), [...platformChannels]);
  });

  it("routes every channel the platform defines in the sheet the image ships", () => {
    const shipped = readIntakeSheet();
    assert.ok(shipped?.routes, "the shipped sheet states its routes");
    for (const channel of platformChannels) {
      assert.equal(typeof shipped.routes[channel], "string", `${channel} has an intake queue`);
    }
  });
});
