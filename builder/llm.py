"""OpenRouter access: synchronous calls, batch jobs, response cache, cost accounting."""
import hashlib
import json
import os
import pathlib
import re
import time

import requests

API = "https://openrouter.ai/api/v1"
CACHE = pathlib.Path(__file__).parent.parent / "build" / "cache"


def _headers():
    return {"Authorization": f"Bearer {os.environ['OPENROUTER_API_KEY']}", "Content-Type": "application/json"}


def _body(model, system, user, max_tokens, extra=None):
    return {**(extra or {}),
        "model": model,
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        "response_format": {"type": "json_object"},
        "temperature": 0.2,
        "max_tokens": max_tokens,
        "usage": {"include": True},
        "provider": {"data_collection": "deny"},
    }


def _key(model, system, user, max_tokens, extra=None):
    """Cache key over everything that shapes the answer."""
    settings = json.dumps({"max_tokens": max_tokens, "extra": extra or {}}, sort_keys=True)
    return hashlib.sha256(f"{model}\n{system}\n{user}\n{settings}".encode()).hexdigest()


def _parse(content):
    content = re.sub(r"^```(?:json)?\s*|\s*```$", "", content.strip())
    return json.loads(content)


def _cached(key):
    path = CACHE / f"{key}.json"
    return json.loads(path.read_text()) if path.exists() else None


def _store(key, record):
    CACHE.mkdir(parents=True, exist_ok=True)
    (CACHE / f"{key}.json").write_text(json.dumps(record, ensure_ascii=False))


def complete(model, system, user, max_tokens=16000, retries=2, extra=None):
    """Returns (parsed JSON, cost in USD, from_cache)."""
    key = _key(model, system, user, max_tokens, extra)
    if (hit := _cached(key)) is not None:
        return hit["result"], 0.0, True
    last = None
    for _ in range(retries + 1):
        try:
            r = requests.post(f"{API}/chat/completions", headers=_headers(),
                              json=_body(model, system, user, max_tokens, extra), timeout=900)
            r.raise_for_status()
            body = r.json()
            choice = body["choices"][0]
            if not choice["message"].get("content"):
                raise ValueError(f"empty content, finish_reason={choice.get('finish_reason')}")
            result = _parse(choice["message"]["content"])
            cost = body.get("usage", {}).get("cost") or 0.0
            _store(key, {"model": model, "result": result, "usage": body.get("usage")})
            return result, cost, False
        except Exception as exc:  # retry on transport and JSON errors
            last = exc
            time.sleep(3)
    raise RuntimeError(f"{model}: {last}")


def batch(model, system, users, max_tokens=32000, poll_seconds=30, log=print, extra=None):
    """Runs prompts through the OpenRouter Batch API. Returns ({custom_id: result}, cost, errors)."""
    results, todo = {}, {}
    for cid, user in users.items():
        key = _key(model, system, user, max_tokens, extra)
        if (hit := _cached(key)) is not None:
            results[cid] = hit["result"]
        else:
            todo[cid] = (key, user)
    if not todo:
        return results, 0.0, {}
    payload = {
        "endpoint": "/v1/chat/completions",
        "model": model,
        "requests": [{"custom_id": cid, "body": {k: v for k, v in _body(model, system, user, max_tokens, extra).items()
                                                if k not in ("model", "usage", "provider")}}
                     for cid, (_, user) in todo.items()],
    }
    r = requests.post(f"{API}/batches", headers=_headers(), data=json.dumps(payload, ensure_ascii=False).encode(), timeout=300)
    if r.status_code >= 400:
        raise RuntimeError(f"batch submit {r.status_code}: {r.text[:500]}")
    job = r.json()
    log(f"batch {job.get('id')} submitted, {len(todo)} requests")
    while True:
        time.sleep(poll_seconds)
        g = requests.get(f"{API}/batches/{job['id']}", headers=_headers(), timeout=120)
        if g.status_code == 429:
            time.sleep(120)
            continue
        g.raise_for_status()
        job = g.json()
        if job["status"] in ("completed", "failed", "expired", "cancelled") and (job["status"] != "completed" or job.get("results") is not None):
            break
        log(f"batch {job['id']}: {job['status']} {job.get('request_counts')}")
    if job["status"] != "completed":
        raise RuntimeError(f"batch ended {job['status']}: {job.get('error')}")
    cost, errors = 0.0, {}
    for item in job["results"]:
        cid = item["custom_id"]
        if item.get("error"):
            errors[cid] = item["error"]
            continue
        body = item["response"].get("body", item["response"])
        cost += (body.get("usage") or {}).get("cost") or 0.0
        try:
            result = _parse(body["choices"][0]["message"]["content"])
        except Exception as exc:
            errors[cid] = str(exc)
            continue
        key, _ = todo[cid]
        _store(key, {"model": model, "result": result, "usage": body.get("usage")})
        results[cid] = result
    if not cost:
        cost = float(job.get("cost") or (job.get("usage") or {}).get("cost") or 0.0)
    requests.delete(f"{API}/batches/{job['id']}", headers=_headers(), timeout=60)
    return results, cost, errors
