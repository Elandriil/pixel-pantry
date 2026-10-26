import { expect, test } from "bun:test";
import { existsSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { ImageStore, imageUrl } from "../src/metadata/images";
import { tmpDir } from "./helpers";

function fake(status = 200) {
  const state = { calls: 0, urls: [] as string[] };
  const fetchFn = (async (url: string | URL | Request) => {
    state.calls++;
    state.urls.push(String(url));
    await new Promise((r) => setTimeout(r, 10));
    return new Response(new Uint8Array([1, 2, 3]), { status });
  }) as unknown as typeof fetch;
  return { state, fetchFn };
}

test("downloads once for two sequential calls", async () => {
  const dir = tmpDir();
  const { state, fetchFn } = fake();
  const store = new ImageStore({ dir, fetchFn });
  const p1 = await store.ensure("w500", "/abc.jpg");
  const p2 = await store.ensure("w500", "/abc.jpg");
  expect(p1).toBe(join(dir, "w500", "abc.jpg"));
  expect(p2).toBe(p1);
  expect(existsSync(p1)).toBe(true);
  expect(state.calls).toBe(1);
  expect(state.urls[0]).toBe("https://image.tmdb.org/t/p/w500/abc.jpg");
  expect(readdirSync(join(dir, "w500"))).toEqual(["abc.jpg"]);
});

test("concurrent calls share one fetch", async () => {
  const { state, fetchFn } = fake();
  const store = new ImageStore({ dir: tmpDir(), fetchFn });
  const [a, b] = await Promise.all([store.ensure("w342", "/x.jpg"), store.ensure("w342", "/x.jpg")]);
  expect(a).toBe(b);
  expect(state.calls).toBe(1);
});

test("non-2xx throws and leaves no file", async () => {
  const dir = tmpDir();
  const { fetchFn } = fake(404);
  const store = new ImageStore({ dir, fetchFn });
  await expect(store.ensure("w500", "/nope.jpg")).rejects.toThrow("image 404");
  expect(existsSync(join(dir, "w500", "nope.jpg"))).toBe(false);
});

test("imageUrl builds api path or null", () => {
  expect(imageUrl("w500", "/abc.jpg")).toBe("/api/images/w500/abc.jpg");
  expect(imageUrl("w500", null)).toBeNull();
});

test("unsafe paths are rejected without fetching", async () => {
  const dir = tmpDir();
  const { state, fetchFn } = fake();
  const store = new ImageStore({ dir, fetchFn });
  for (const bad of ["/..", "/.", "/", "/x?y.jpg", "/x#y.jpg", "/abc.txt"]) {
    await expect(store.ensure("w500", bad)).rejects.toThrow("image invalid path");
    expect(imageUrl("w500", bad)).toBeNull();
  }
  expect(state.calls).toBe(0);
});

test("an image download that never answers is rejected with image timeout", async () => {
  const fetchFn = (() => new Promise<Response>(() => {})) as unknown as typeof fetch;
  const store = new ImageStore({ dir: tmpDir(), fetchFn, timeoutMs: 20 });
  await expect(store.ensure("w500", "/slow.jpg")).rejects.toThrow("image timeout");
});
