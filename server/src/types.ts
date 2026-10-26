import type { Track } from "./library/probe";

export type Category = 'tv' | 'anime' | 'movies';

export type FetchFn = typeof fetch;

export interface Progress { position: number; duration: number; watched: boolean }
export interface ItemSummary { id: number; type: 'episode' | 'movie'; category: Category; title: string; showId: number | null; showTitle: string | null; season: number | null; episode: number | null; episodeEnd: number | null; year: number | null; runtime: number | null; overview: string | null; poster: string | null; backdrop: string | null; still: string | null; airDate: string | null; progress: Progress | null; addedAt: number }
export interface ShowSummary { id: number; category: Category; title: string; year: number | null; overview: string | null; poster: string | null; backdrop: string | null; rating: number | null; genres: string[]; episodeCount: number; unwatchedCount: number; matched: boolean; addedAt: number }
export type Card = { kind: 'show'; show: ShowSummary; nextUp?: ItemSummary } | { kind: 'item'; item: ItemSummary; reason?: 'resume' | 'next' }
export interface Row { key: 'continue' | 'recent' | 'all'; title: string; cards: Card[] }
export interface CategoryPage { category: Category | 'home'; rows: Row[] }
export interface SeasonDetail { number: number; name: string; poster: string | null; episodes: ItemSummary[] }
export interface ShowDetail { show: ShowSummary; seasons: SeasonDetail[]; resume: ItemSummary | null }
export interface ItemDetail { item: ItemSummary; streamUrl: string; fileName: string; fileSize: number; audio: Track[]; subtitles: SubtitleTrack[]; next: ItemSummary | null; prev: ItemSummary | null; subtitleChoice: string | null; audioChoice: string | null }
export interface SubtitleTrack { id: string /* "embedded:3" | "sidecar:<n>" */; label: string; lang: string | null; url: string | null /* null for embedded */; format: 'srt' | 'vtt' | 'ass' | 'embedded' }
export interface Settings { skipBackSeconds: number; skipForwardSeconds: number; autoplayNext: boolean }

/** A value fixed at construction or read fresh on every use (e.g. a getter over the live config). */
export type Lazy<T> = T | (() => T);
export const resolve = <T>(v: Lazy<T>): T => (typeof v === "function" ? (v as () => T)() : v);
