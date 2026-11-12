"""Where the prompt tokens go, split by part, without calling a model: the segment pass of chapter N and the
book-level lexical pass over the built chapters.

Builds the prompts exactly as run_chapter.py and run_lexicon.py do. Token counts use tiktoken's o200k_base as a
stand-in for the providers' tokenizers, so they are estimates; calls already in the response cache add the
provider's real prompt and completion tokens and cost. Writes build/chNN/prompt_audit.json.

Usage: .venv/bin/python builder/prompt_audit.py N
"""
import json
import re
import sys

import tiktoken

import chapter_build
import lexicon_build
import llm
import model_prompts

SEGMENT_PARTS = [("context", r"CONTEXT: "), ("source tokens", r"\[\S+\] "), ("published translation", r"RU: "),
                 ("footnotes", r"NOTE: "), ("author footnotes", r"AUTHOR NOTE: "), ("reference excerpt", r"")]
LEXICAL_PARTS = [("idioms and phrases", r"D[FP]\d+: "), ("dictionary senses", r"D[\d.]+: "),
                 ("dictionary examples", r"  e\.g\. "), ("occurrences", r"\d+\. "), ("headers", r"(## |DICT |$)")]
# An occurrence line: passage => literal | RU: published translation (в сцене: names)
OCCURRENCE_MARKS = [(" (в сцене: ", "names hint"), (" | RU: ", "published translation"), (" => ", "literal")]


def classify(prompt, parts, encoder):
    totals = {name: 0 for name, _ in parts}
    for line in prompt.split("\n"):
        name = next(n for n, pattern in parts if re.match(pattern, line))
        if name == "occurrences":
            for mark, part in OCCURRENCE_MARKS:
                if mark in line:
                    line, tail = line.split(mark, 1)
                    totals[part] = totals.get(part, 0) + len(encoder.encode(mark + tail))
        totals[name] += len(encoder.encode(line)) + 1  # + the newline
    return totals


def cached_usage(model, system, prompts, max_tokens, extra):
    actual = {"calls_cached": 0, "prompt_tokens": 0, "completion_tokens": 0, "cost": 0.0}
    for p in prompts:
        hit = llm._cached(llm._key(model, system, p, max_tokens, extra))
        if hit and hit.get("usage"):
            actual["calls_cached"] += 1
            actual["prompt_tokens"] += hit["usage"].get("prompt_tokens") or 0
            actual["completion_tokens"] += hit["usage"].get("completion_tokens") or 0
            actual["cost"] += hit["usage"].get("cost") or 0.0
    return actual


def report(model, system, prompts, parts, max_tokens, extra, encoder):
    totals = {}
    for p in prompts:
        for k, v in classify(p, parts, encoder).items():
            totals[k] = totals.get(k, 0) + v
    totals["system prompt"] = len(encoder.encode(system)) * len(prompts)
    return {"calls": len(prompts), "estimated_input_tokens": sum(totals.values()),
            "parts": dict(sorted(totals.items(), key=lambda kv: -kv[1])),
            "cached_actual": cached_usage(model, system, prompts, max_tokens, extra)}


def audit(c, lexicon=None):
    """c: chapter_build.prepare(N); lexicon: lexicon_build.prepare() or None when no chapter is built yet."""
    encoder = tiktoken.get_encoding("o200k_base")
    out = {"segment": report(chapter_build.SEGMENT_MODEL, c.segment_system, list(c.prompts.values()), SEGMENT_PARTS, 32000,
                             None, encoder)}
    if lexicon is not None:
        blocks = lexicon_build.blocks(lexicon, [key for key, _ in lexicon.groups])
        prompts = ["\n\n".join(call) for call in model_prompts.pack_calls([b[2] for b in blocks.values()], lexicon_build.CALL_CHARS)]
        out["lexical"] = report(lexicon_build.LEXICAL_MODEL, model_prompts.LEXICAL_SYSTEM, prompts, LEXICAL_PARTS, 16000,
                                {"reasoning": {"enabled": False}}, encoder)
        out["lexical"]["chapters"] = sorted(lexicon.chapters)
    return out


def main():
    number = int(sys.argv[1])
    c = chapter_build.prepare(number)
    built = any((chapter_build.ROOT / "build").glob("ch*/chapter.json"))
    result = audit(c, lexicon_build.prepare() if built else None)
    (c.out / "prompt_audit.json").write_text(json.dumps(result, ensure_ascii=False, indent=1))
    for name, r in result.items():
        scope = f" (chapters {r['chapters']})" if "chapters" in r else ""
        print(f"{name}{scope}: {r['calls']} calls, ~{r['estimated_input_tokens']:,} input tokens")
        for part, n in r["parts"].items():
            print(f"  {part:24} {n:>9,}  {n / r['estimated_input_tokens']:6.1%}")
        a = r["cached_actual"]
        if a["calls_cached"]:
            print(f"  cached {a['calls_cached']}/{r['calls']} calls: {a['prompt_tokens']:,} prompt, "
                  f"{a['completion_tokens']:,} completion tokens, ${a['cost']:.3f}")


if __name__ == "__main__":
    main()
