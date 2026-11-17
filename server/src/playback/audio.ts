import type { Track } from "../library/probe";

/**
 * A show's audio language. Picking an audio track on an episode sets the language of that track for the whole
 * show; from then on every episode of the show reports the track of that language as its audio choice, so the
 * player starts in it. The language is kept, not the track number: the track order differs between releases.
 */

const PREFIX = "audio:";

/** ISO 639-2 codes (the T and the B form) of the languages files are commonly tagged with, as ISO 639-1. */
const TWO_LETTER: Record<string, string> = {
  ara: "ar", bul: "bg", ces: "cs", cze: "cs", chi: "zh", zho: "zh", dan: "da", deu: "de", ger: "de", dut: "nl",
  nld: "nl", ell: "el", gre: "el", eng: "en", est: "et", fin: "fi", fra: "fr", fre: "fr", heb: "he", hin: "hi",
  hrv: "hr", hun: "hu", ind: "id", isl: "is", ice: "is", ita: "it", jpn: "ja", kor: "ko", lav: "lv", lit: "lt",
  nob: "nb", nor: "no", pol: "pl", por: "pt", ron: "ro", rum: "ro", rus: "ru", slk: "sk", slo: "sk", slv: "sl",
  spa: "es", srp: "sr", swe: "sv", tha: "th", tur: "tr", ukr: "uk", vie: "vi",
};

/** A language tag in one comparable form (`jpn`, `ja`, `ja-JP` → `ja`); null for none or undetermined. */
export function languageKey(code: string | null | undefined): string | null {
  const c = code?.trim().toLowerCase().split(/[-_]/)[0];
  if (!c || c === "und") return null;
  return TWO_LETTER[c] ?? c;
}

const trackOf = (tracks: Track[], choice: string | null): Track | null =>
  tracks.find((t) => `${PREFIX}${t.index}` === choice) ?? null;

/** The track a player starts with when nothing was chosen: the file's default one, else the first. */
const defaultTrack = (tracks: Track[]): Track | null => tracks.find((t) => t.default) ?? tracks[0] ?? null;

/**
 * The audio choice to report for an item. Without `showLanguage` it is the item's stored choice. With one, the
 * item's own choice stands when it is a track of that language (the exact track among several) or an untagged
 * track; otherwise the file's track of that language is chosen, the default-flagged one first. A file without the
 * language keeps the item's choice.
 */
export function audioChoiceFor(tracks: Track[], itemChoice: string | null, showLanguage: string | null): string | null {
  if (showLanguage === null) return itemChoice;
  const own = trackOf(tracks, itemChoice);
  if (own) {
    const lang = languageKey(own.language);
    if (lang === null || lang === showLanguage) return itemChoice;
  }
  const inLanguage = tracks.filter((t) => languageKey(t.language) === showLanguage);
  const pick = inLanguage.find((t) => t.default) ?? inLanguage[0];
  return pick ? `${PREFIX}${pick.index}` : itemChoice;
}

/**
 * The language a pick of `picked` sets for the show, or null when it sets none: the track has no language tag,
 * or its language is the one that plays anyway (`current`: the choice reported for the item; without one, the
 * file's default track). The second case matters because a client saves both choices together: a subtitle pick
 * repeats the audio track that is playing and must not be taken for an audio pick.
 */
export function pickedLanguage(tracks: Track[], current: string | null, picked: string): string | null {
  const lang = languageKey(trackOf(tracks, picked)?.language);
  if (lang === null) return null;
  const playing = trackOf(tracks, current) ?? defaultTrack(tracks);
  return languageKey(playing?.language) === lang ? null : lang;
}
