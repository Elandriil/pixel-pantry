import { appendFileSync, mkdirSync, renameSync, statSync } from "node:fs";
import { dirname } from "node:path";

type Level = "INFO" | "WARN" | "ERROR";

export class Logger {
  private ring: string[] = [];
  private ringSize: number;
  private maxBytes: number;

  constructor(private filePath: string, opts: { ringSize?: number; maxBytes?: number } = {}) {
    this.ringSize = opts.ringSize ?? 500;
    this.maxBytes = opts.maxBytes ?? 5 * 1024 * 1024;
    mkdirSync(dirname(filePath), { recursive: true });
  }

  info(msg: string, data?: object): void { this.write("INFO", msg, data); }
  warn(msg: string, data?: object): void { this.write("WARN", msg, data); }
  error(msg: string, data?: object): void { this.write("ERROR", msg, data); }

  recent(n?: number): string[] {
    return n === undefined ? [...this.ring] : this.ring.slice(-n);
  }

  private write(level: Level, msg: string, data?: object): void {
    const line = `${new Date().toISOString()} ${level} ${msg}${data ? " " + JSON.stringify(data) : ""}`;
    this.ring.push(line);
    if (this.ring.length > this.ringSize) this.ring.shift();
    try {
      this.rotateIfNeeded();
      appendFileSync(this.filePath, line + "\n");
    } catch {}
  }

  private rotateIfNeeded(): void {
    try {
      if (statSync(this.filePath).size > this.maxBytes) renameSync(this.filePath, this.filePath + ".1");
    } catch {}
  }
}
