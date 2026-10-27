import { Bonjour } from "bonjour-service";
import { hostname } from "node:os";
import type { Logger } from "./log";

export interface PublishOptions {
  name: string;
  type: string;
  port: number;
  host: string;
  txt: Record<string, string>;
}

/**
 * The SRV target and A-record name. bonjour-service uses the bare OS hostname (`DESKTOP-2CMEQHG` on Windows), which
 * Android's NSD then tries to resolve as a DNS name and gives up; a `.local` name is answered over mDNS.
 */
export function mdnsHost(name: string = hostname()): string {
  const base = name.trim().replace(/\.+$/, "");
  return /\.local$/i.test(base) ? base : `${base}.local`;
}

/** The slice of `bonjour-service` used here, so tests can inject a fake. */
export interface BonjourLike {
  publish(opts: PublishOptions): { stop(cb?: () => void): void };
  destroy(): void;
}

const message = (err: unknown) => (err instanceof Error ? err.message : String(err));

/**
 * bonjour-service's default error callback throws, and multicast-dns emits `error` with no listener,
 * so both are routed to `onError` to keep a socket failure from becoming an uncaught exception.
 */
const defaultFactory = (onError: (err: unknown) => void): BonjourLike => {
  const bonjour = new Bonjour({}, onError);
  const mdns = (bonjour as unknown as { server?: { mdns?: { on?: (ev: string, cb: (err: unknown) => void) => void } } })
    .server?.mdns;
  if (typeof mdns?.on === "function") mdns.on("error", onError);
  return bonjour as unknown as BonjourLike;
};

/** Advertises the server on the LAN via mDNS. Never throws; failures are logged. */
export function advertise(port: number, log: Logger, factory: (onError: (err: unknown) => void) => BonjourLike = defaultFactory): { stop(): Promise<void> } {
  let bonjour: BonjourLike | null = null;
  let service: ReturnType<BonjourLike["publish"]> | null = null;
  try {
    bonjour = factory((err) => log.error("mDNS error", { error: message(err) }));
    const host = mdnsHost();
    service = bonjour.publish({ name: "Pixel Pantry", type: "pixelpantry", port, host, txt: { version: "1" } });
    log.info("mDNS advertising", { type: "pixelpantry", port, host });
  } catch (err) {
    log.error("mDNS advertise failed", { error: message(err) });
  }
  return {
    async stop() {
      try {
        if (service) await new Promise<void>((done) => service!.stop(() => done()));
        bonjour?.destroy();
      } catch (err) {
        log.error("mDNS stop failed", { error: message(err) });
      } finally {
        service = null;
        bonjour = null;
      }
    },
  };
}
