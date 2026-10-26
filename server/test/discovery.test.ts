import { expect, test } from "bun:test";
import { join } from "node:path";
import { advertise, type BonjourLike } from "../src/discovery";
import { Logger } from "../src/log";
import { tmpDir } from "./helpers";

function fakeBonjour() {
  const published: unknown[] = [];
  let stopped = 0;
  let destroyed = 0;
  let onError: ((err: unknown) => void) | null = null;
  const factory = (handler: (err: unknown) => void): BonjourLike => {
    onError = handler;
    return {
    publish(opts) {
      published.push(opts);
      return { stop: (cb?: () => void) => { stopped++; cb?.(); } };
    },
    destroy: () => { destroyed++; },
    };
  };
  return { factory, published, stopped: () => stopped, destroyed: () => destroyed, onError: () => onError };
}

test("advertise publishes the pixelpantry service on the port and stop() unpublishes", async () => {
  const log = new Logger(join(tmpDir(), "server.log"));
  const fake = fakeBonjour();
  const ad = advertise(8765, log, fake.factory);
  expect(fake.published).toEqual([{ name: "Pixel Pantry", type: "pixelpantry", port: 8765, txt: { version: "1" } }]);
  await ad.stop();
  expect(fake.stopped()).toBe(1);
  expect(fake.destroyed()).toBe(1);
});

test("advertise logs and swallows publish errors", async () => {
  const log = new Logger(join(tmpDir(), "server.log"));
  const ad = advertise(8765, log, () => {
    throw new Error("no multicast");
  });
  await ad.stop();
  expect(log.recent().some((l) => l.includes("ERROR") && l.includes("no multicast"))).toBe(true);
});

test("advertise passes an error handler that logs runtime mDNS errors instead of throwing", () => {
  const log = new Logger(join(tmpDir(), "server.log"));
  const fake = fakeBonjour();
  advertise(8765, log, fake.factory);
  const handler = fake.onError();
  expect(handler).toBeFunction();
  expect(() => handler!(new Error("EADDRINUSE 5353"))).not.toThrow();
  expect(log.recent().some((l) => l.includes("ERROR mDNS error") && l.includes("EADDRINUSE 5353"))).toBe(true);
});
