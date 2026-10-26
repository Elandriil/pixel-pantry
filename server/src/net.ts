import { networkInterfaces } from "node:os";

/** Non-internal IPv4 addresses of this machine. */
export function lanAddresses(): string[] {
  const out: string[] = [];
  for (const list of Object.values(networkInterfaces())) {
    for (const a of list ?? []) if (a.family === "IPv4" && !a.internal) out.push(a.address);
  }
  return out;
}

export const DEFAULT_TIMEOUT_MS = 20_000;

/**
 * Runs `run` with an abort signal that fires after `ms`. On expiry the promise rejects with `<service> timeout`,
 * also when `run` ignores the signal; an abort error from `run` itself is mapped to the same message.
 */
export function withTimeout<T>(service: string, ms: number, run: (signal: AbortSignal) => Promise<T>): Promise<T> {
  const signal = AbortSignal.timeout(ms);
  const timeout = () => new Error(`${service} timeout`);
  return new Promise<T>((resolve, reject) => {
    const onAbort = () => reject(timeout());
    signal.addEventListener("abort", onAbort, { once: true });
    Promise.resolve()
      .then(() => run(signal))
      .then(resolve, (err) => reject(signal.aborted ? timeout() : err))
      .finally(() => signal.removeEventListener("abort", onAbort));
  });
}
