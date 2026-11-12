"""The published translation per sentence group: sentence alignment turned into references, and how far each group's
length departs from the chapter's usual ratio (doubtful alignments are marked for checking)."""
import math


# Robust z-score below which an alignment score is unusual for a translation of that length; such groups are judged
# by the segment model. -1.5 robust deviations sends about the lowest tenth of chapter 1 to the model.
CHECK_BELOW_Z = -1.5


MIN_GROUPS_FOR_FIT = 40


def sentence_references(sentences, alignment):
    """Published translation per sentence group from an alignment.

    Returns (references, ref_of): references[k] = {"text": Russian text or None if the translator left the
    sentences out, "score": cosine or None, "notes": ids of the footnotes in it}; ref_of maps every sid to its
    reference index. A translator's insertion (0:1 group) joins the previous group.
    """
    ru = alignment["ru"]
    groups, ref_of, pending = [], {}, []
    for g in alignment["groups"]:
        if not g["es"]:
            if groups:
                groups[-1]["ru"] += g["ru"]
            else:
                pending += g["ru"]
            continue
        groups.append({"ru": pending + g["ru"], "score": g["score"]})
        pending = []
        for sid in g["es"]:
            ref_of[sid] = len(groups) - 1
    if set(ref_of) != {s.sid for s in sentences if s.paragraph > 0}:  # the title (paragraph 0) is not aligned
        raise ValueError("alignment.json does not match the chapter's sentences: rerun align.py --force")
    references = [{"text": " ".join(ru[k]["text"] for k in g["ru"]) or None, "score": g["score"],
                   "notes": [i for k in g["ru"] for i in ru[k].get("notes", [])]} for g in groups]
    return references, ref_of


def score_deviations(references):
    """Robust z of each group's alignment score against what is usual for its length in this chapter.

    The median score barely depends on length, but its spread does: short sentences scatter widely (chapter 1:
    median absolute deviation 0.13 for 1-3 words, 0.035 above 40 words), so one fixed threshold flags short exact
    translations. Fits the median and the spread as linear functions of log(words) over length bins, then returns
    (score - median) / (1.4826 * spread) per group, None for groups without a score or text.
    """
    points = [(math.log(len(r["text"].split())), r["score"]) for r in references if r["score"] is not None and r["text"]]
    if len(points) < MIN_GROUPS_FOR_FIT:
        return [None] * len(references)
    points.sort()
    bins = [points[i * len(points) // 7:(i + 1) * len(points) // 7] for i in range(7)]
    centers, medians, spreads = [], [], []
    for b in bins:
        xs, ys = [p[0] for p in b], sorted(p[1] for p in b)
        median = ys[len(ys) // 2]
        centers.append(sum(xs) / len(xs))
        medians.append(median)
        spreads.append(max(1e-3, sorted(abs(y - median) for y in ys)[len(ys) // 2]))

    def line(xs, ys):
        mx, my = sum(xs) / len(xs), sum(ys) / len(ys)
        slope = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / (sum((x - mx) ** 2 for x in xs) or 1)
        return lambda x: my + slope * (x - mx)
    center, log_spread = line(centers, medians), line(centers, [math.log(s) for s in spreads])
    out = []
    for r in references:
        if r["score"] is None or not r["text"]:
            out.append(None)
            continue
        x = math.log(len(r["text"].split()))
        out.append((r["score"] - center(x)) / (1.4826 * math.exp(log_spread(x))))
    return out
