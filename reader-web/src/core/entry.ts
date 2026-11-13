/**
 * The labels to show over an entry's senses in the word card, as in the Android reader (senseHeadings): the label of
 * each sense where it changes from the sense before ("сущ." over 1.1, "гл." over 2.1), and none at all when the whole
 * entry has one label (it stands at the top of the card already). Senses without a label get no heading.
 */
export function senseHeadings(labels: (string | null)[]): (string | null)[] {
  if (new Set(labels.filter((g) => g != null)).size < 2) return labels.map(() => null)
  return labels.map((g, i) => (g != null && (i === 0 || labels[i - 1] !== g) ? g : null))
}
