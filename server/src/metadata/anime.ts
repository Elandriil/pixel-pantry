/** episodeCounts[i] is the episode count of season i+1 (specials excluded). */
export function absoluteToSeasonEpisode(
  absolute: number,
  episodeCounts: number[],
): { season: number; episode: number } | null {
  if (absolute < 1) return null;
  let remaining = absolute;
  for (let i = 0; i < episodeCounts.length; i++) {
    if (remaining <= episodeCounts[i]) return { season: i + 1, episode: remaining };
    remaining -= episodeCounts[i];
  }
  return null;
}
