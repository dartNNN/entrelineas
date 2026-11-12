"""Answers of the segment pass: checks of the segments a model returned and the literal translation of a token."""
import re


def check_segments(sentence, ann):
    """Validates coverage; repairs a missing tail of punctuation. Returns list of problems."""
    segs = ann.get("s") or []
    n = len(sentence.tokens)
    problems, expected = [], 0
    if segs and isinstance(segs[-1], list) and len(segs[-1]) >= 3 and segs[-1][1] >= n:
        segs[-1][1] = n - 1  # models often count one index past the end
    for seg in segs:
        if not isinstance(seg, list) or len(seg) < 3:
            problems.append("bad segment shape")
            continue
        if seg[0] != expected:
            problems.append(f"gap/overlap at {expected}->{seg[0]}")
        expected = seg[1] + 1
    if expected < n and segs and all(not re.search(r"\w", t.text) for t in sentence.tokens[expected:]):
        segs[-1][1] = n - 1
        expected = n
    if expected != n:
        problems.append(f"covers {expected}/{n}")
    return problems


def segment_literal(ann, token):
    for seg in (ann or {}).get("s", []):
        if isinstance(seg, list) and len(seg) >= 3 and seg[0] <= token <= seg[1]:
            return seg[2]
    return ""
