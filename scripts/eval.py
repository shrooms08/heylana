#!/usr/bin/env python3
"""Heylana's eval: 20 captured questions replayed against the deployed worker.

    python3 scripts/eval.py                # all twenty
    python3 scripts/eval.py --skip chat-opinion   # leave one (or more) out
    python3 scripts/eval.py --only send-sol

The questions are scripts/eval/cases.json, written by the app's unit test EvalCasesTest with
the app's own prompt code, so they are exactly what the phone sends: 10 chat, 5 screen
questions, 3 send-prepares on devnet and 2 lesson turns. Each reply is checked for
properties, not exact words:

  json     the reply is the JSON the app reads (a say, or a send's action)
  cap      say is within the app's word cap for that route
  address  no full Solana address in say (the app only ever shows them shortened)
  numbers  on a wallet screen, every number in say is one the screen shows
  point    point_at, and every segment's point_at, is null or a real id on the screen
  mention  the facts the question needs are there (who made Heylana, Abuja, Argentina…)
  lesson   a lesson turn has one check question and a verdict field
  send     the proposed send is exactly what was said, and /send/prepare answers it
           with a quote or a plain refusal. Nothing is ever confirmed, built or signed.

Every /chat is a real model call: twenty per full run, counted against the eval's own
throwaway wallet, never the phone's. The wallet (an ed25519 key made here) and a random
device id are kept in scripts/eval/.state.json, which git ignores. Free gives that wallet
20 welcome talks and 30 a month; set HEYLANA_JUDGE_CODE to lift the cap for the run.

The proxy address comes from HEYLANA_PROXY, else heylana.proxyUrl in local.properties.
Standard library only.
"""
import argparse, hashlib, json, os, re, secrets, sys, time, urllib.error, urllib.request, uuid

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CASES = os.path.join(ROOT, "scripts", "eval", "cases.json")
STATE = os.path.join(ROOT, "scripts", "eval", ".state.json")

# ------------------------------------------------------------------ ed25519 (RFC 8032)

P = 2 ** 255 - 19
L = 2 ** 252 + 27742317777372353535851937790883648493
D = -121665 * pow(121666, P - 2, P) % P
I = pow(2, (P - 1) // 4, P)


def _recover_x(y, sign):
    x2 = (y * y - 1) * pow(D * y * y + 1, P - 2, P)
    x = pow(x2, (P + 3) // 8, P)
    if (x * x - x2) % P != 0:
        x = x * I % P
    if x % 2 != sign:
        x = P - x
    return x


_BY = 4 * pow(5, P - 2, P) % P
_B = (_recover_x(_BY, 0), _BY, 1, _recover_x(_BY, 0) * _BY % P)


def _add(p, q):
    a = (p[1] - p[0]) * (q[1] - q[0]) % P
    b = (p[1] + p[0]) * (q[1] + q[0]) % P
    c = 2 * p[3] * q[3] * D % P
    d = 2 * p[2] * q[2] % P
    e, f, g, h = b - a, d - c, d + c, b + a
    return (e * f % P, g * h % P, f * g % P, e * h % P)


def _mul(s, p):
    q = (0, 1, 1, 0)
    while s > 0:
        if s & 1:
            q = _add(q, p)
        p = _add(p, p)
        s >>= 1
    return q


def _encode(p):
    zi = pow(p[2], P - 2, P)
    x, y = p[0] * zi % P, p[1] * zi % P
    return int.to_bytes(y | ((x & 1) << 255), 32, "little")


def _expand(seed):
    h = hashlib.sha512(seed).digest()
    a = int.from_bytes(h[:32], "little")
    a &= (1 << 254) - 8
    a |= 1 << 254
    return a, h[32:]


def public_key(seed):
    return _encode(_mul(_expand(seed)[0], _B))


def sign(seed, message):
    a, prefix = _expand(seed)
    pub = _encode(_mul(a, _B))
    r = int.from_bytes(hashlib.sha512(prefix + message).digest(), "little") % L
    big_r = _encode(_mul(r, _B))
    k = int.from_bytes(hashlib.sha512(big_r + pub + message).digest(), "little") % L
    return big_r + int.to_bytes((r + k * a) % L, 32, "little")


B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"


def b58(data):
    n = int.from_bytes(data, "big")
    out = ""
    while n:
        n, rem = divmod(n, 58)
        out = B58[rem] + out
    return "1" * (len(data) - len(data.lstrip(b"\0"))) + out

# ------------------------------------------------------------------ the worker


def proxy_url():
    if os.environ.get("HEYLANA_PROXY"):
        return os.environ["HEYLANA_PROXY"].rstrip("/")
    with open(os.path.join(ROOT, "local.properties")) as f:
        for line in f:
            if line.startswith("heylana.proxyUrl="):
                return line.split("=", 1)[1].strip().rstrip("/")
    sys.exit("No proxy address: set HEYLANA_PROXY or heylana.proxyUrl in local.properties.")


class Worker:
    def __init__(self, base, device, session=None):
        self.base, self.device, self.session = base, device, session

    def post(self, path, body):
        # Cloudflare turns away Python's default User-Agent (error 1010) before the worker sees it.
        headers = {"content-type": "application/json", "X-Heylana-Device": self.device, "User-Agent": "heylana-eval/1"}
        if self.session:
            headers["Authorization"] = "Bearer " + self.session
        req = urllib.request.Request(self.base + "/" + path, data=json.dumps(body).encode(), headers=headers, method="POST")
        started = time.time()
        try:
            with urllib.request.urlopen(req, timeout=60) as res:
                return res.status, res.read().decode(), int((time.time() - started) * 1000)
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode(errors="ignore"), int((time.time() - started) * 1000)


def load_state():
    if os.path.exists(STATE):
        with open(STATE) as f:
            return json.load(f)
    state = {"device": str(uuid.uuid4()), "seed": secrets.token_hex(32)}
    save_state(state)
    return state


def save_state(state):
    os.makedirs(os.path.dirname(STATE), exist_ok=True)
    with open(STATE, "w") as f:
        json.dump(state, f)
    os.chmod(STATE, 0o600)


def signed_in(worker, state):
    """The eval's own throwaway wallet: a sign-in message signed with its key, never a transaction."""
    seed = bytes.fromhex(state["seed"])
    pubkey = b58(public_key(seed))
    status, text, _ = worker.post("wallet/challenge", {"pubkey": pubkey})
    if status != 200:
        sys.exit(f"wallet/challenge: {status} {text[:200]}")
    challenge = json.loads(text)
    signature = b58(sign(seed, challenge["message"].encode()))
    status, text, _ = worker.post("wallet/verify", {"pubkey": pubkey, "nonce": challenge["nonce"], "signature": signature})
    if status != 200:
        sys.exit(f"wallet/verify: {status} {text[:200]}")
    worker.session = json.loads(text)["session"]
    code = os.environ.get("HEYLANA_JUDGE_CODE")
    if code:
        status, _, _ = worker.post("judge", {"code": code})
        print(f"judge code: {'accepted' if status == 200 else 'refused ' + str(status)}")
    return pubkey

# ------------------------------------------------------------------ the checks

ADDRESS = re.compile(r"[1-9A-HJ-NP-Za-km-z]{32,44}")
NUMBER = re.compile(r"\d[\d,]*(?:\.\d+)?")


def reply_object(body):
    """The reply the app reads: the first JSON object with a say or an action in the text blocks."""
    try:
        content = json.loads(body).get("content") or []
    except ValueError:
        return None
    text = "\n".join(b.get("text", "") for b in content if b.get("type") == "text")
    decoder = json.JSONDecoder()
    for i, ch in enumerate(text):
        if ch != "{":
            continue
        try:
            obj, _ = decoder.raw_decode(text[i:])
        except ValueError:
            continue
        if isinstance(obj, dict) and ("say" in obj or "action" in obj):
            return obj
    return None


def say_text(obj):
    say = obj.get("say")
    if isinstance(say, list):
        return " ".join(str(p.get("text", "")) for p in say if isinstance(p, dict))
    return say if isinstance(say, str) else ""


def pointers(obj):
    found = [obj.get("point_at")]
    if isinstance(obj.get("say"), list):
        found += [p.get("point_at") for p in obj["say"] if isinstance(p, dict)]
    return [p for p in found if p is not None]


def words(text):
    return len(text.split())


def check(case, status, body, worker):
    """Every property this case has, as (name, passed, note)."""
    expect = case["expect"]
    results = []
    obj = reply_object(body) if status == 200 else None
    results.append(("json", obj is not None, "" if obj else f"status {status}"))
    if obj is None:
        return results, None
    say = say_text(obj)
    if case["kind"] != "send":
        cap = expect.get("cap", 60)
        results.append(("cap", 0 < words(say) <= cap, f"{words(say)}/{cap} words"))
    results.append(("address", not ADDRESS.search(say), ""))
    if expect.get("numbers_from"):
        screen = expect["numbers_from"].replace(",", "")
        invented = [n for n in NUMBER.findall(say) if n.replace(",", "").rstrip(".") not in screen]
        results.append(("numbers", not invented, ("not on screen: " + ", ".join(invented)) if invented else ""))
    if "ids" in expect:
        bad = [p for p in pointers(obj) if not isinstance(p, int) or p not in expect["ids"]]
        results.append(("point", not bad, f"bad ids {bad}" if bad else ""))
    for fact in expect.get("mentions", []):
        results.append(("mention", fact.lower() in say.lower(), fact))
    if expect.get("mentions_any"):
        results.append(("mention", any(f in say for f in expect["mentions_any"]), "/".join(expect["mentions_any"])))
    if case["kind"] == "lesson":
        check_q = obj.get("check")
        results.append(("lesson", isinstance(check_q, str) and check_q.strip().endswith("?") and "verdict" in obj, ""))
    prepared = None
    if case["kind"] == "send":
        action = obj.get("action") or {}
        same = (action.get("to") == expect["to"] and str(action.get("token", "")).upper() == expect["token"]
                and expect["amount"] is not None and _same_amount(action.get("amount"), expect["amount"]))
        results.append(("send", same, f"proposed {action.get('amount')} {action.get('token')} to {str(action.get('to'))[:4]}…"))
        if same:
            # Prepared and checked by the worker, never confirmed, never built, never signed.
            p_status, p_body, _ = worker.post("send/prepare", {
                "to": action["to"], "amount": action["amount"], "token": action["token"],
                "said": case["body"]["said"],
            })
            reason = _reason(p_body)
            results.append(("prepare", p_status < 500, f"{p_status}{' ' + reason if reason else ''}"))
            prepared = p_status
    return results, obj


def _same_amount(a, b):
    try:
        return abs(float(a) - float(b)) < 1e-12
    except (TypeError, ValueError):
        return False


def _reason(body):
    try:
        return json.loads(body).get("reason", "")
    except ValueError:
        return ""


def cache_read(body):
    try:
        usage = json.loads(body).get("usage") or {}
        return int(usage.get("cache_read_input_tokens") or 0), int(usage.get("cache_creation_input_tokens") or 0)
    except ValueError:
        return 0, 0

# ------------------------------------------------------------------ run


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--skip", nargs="*", default=[], help="case ids to leave out")
    parser.add_argument("--only", nargs="*", default=[], help="run only these case ids")
    args = parser.parse_args()

    with open(CASES) as f:
        cases = json.load(f)
    cases = [c for c in cases if c["id"] not in args.skip and (not args.only or c["id"] in args.only)]

    state = load_state()
    worker = Worker(proxy_url(), state["device"])
    wallet = signed_in(worker, state)
    print(f"eval wallet {wallet[:4]}…{wallet[-4:]}, {len(cases)} questions, one /chat each\n")

    rows, failed, calls = [], 0, 0
    for case in cases:
        status, body, ms = worker.post("chat", case["body"])
        calls += 1
        results, obj = check(case, status, body, worker)
        read, write = cache_read(body)
        ok = all(passed for _, passed, _ in results)
        failed += 0 if ok else 1
        notes = "; ".join(f"{n}: {note}" if note else n for n, passed, note in results if not passed)
        say = say_text(obj) if obj else ""
        rows.append((case["id"], case["kind"], "PASS" if ok else "FAIL", str(words(say)), f"{read}/{write}", f"{ms}", notes))

    headers = ("case", "kind", "result", "words", "cache r/w", "ms", "failed checks")
    widths = [max(len(str(r[i])) for r in rows + [headers]) for i in range(len(headers))]
    line = "  ".join(h.ljust(w) for h, w in zip(headers, widths))
    print(line)
    print("-" * len(line))
    for r in rows:
        print("  ".join(str(v).ljust(w) for v, w in zip(r, widths)))
    print(f"\n{len(rows) - failed} of {len(rows)} passed, {calls} /chat calls")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
