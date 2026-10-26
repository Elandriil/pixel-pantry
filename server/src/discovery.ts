import { Bonjour } from "bonjour-service";
import type { Logger } from "./log";

export interface PublishOptions {
  name: string;
  type: string;
  port: number;
  txt: Record<string, string>;
}

/** The slice of `bonjour-service` used here, so tests can inject a fake. */
export interface BonjourLike {
  publish(opts: PublishOptions): { stop(cb?: () => void): void };
  destroy(): void;
}

const defaultFactory = (): BonjourLike => new Bonjour() as unknown as BonjourLike;
const message = (err: unknown) => (err instanceof Error ? err.message : String(err));

/** Advertises the server on the LAN via mDNS. Never throws; failures are logged. */
export function advertise(port: number, log: Logger, factory: () => BonjourLike = defaultFactory): { stop(): Promise<void> } {
  let bonjour: BonjourLike | null = null;
  let service: ReturnType<BonjourLike["publish"]> | null = null;
  try {
    bonjour = factory();
    service = bonjour.publish({ name: "Pixel Pantry", type: "pixelpantry", port, txt: { version: "1" } });
    log.info("mDNS advertising", { type: "pixelpantry", port });
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
