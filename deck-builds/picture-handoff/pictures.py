"""Contact sheets and card edits for the remaining deck pictures.

    python3 pictures.py sheets japanese_candidates.jsonl sheets_ja/
        One JPEG per 6 cards: the card's Portuguese front on the left, up to 4 numbered candidates.

    python3 pictures.py edits japanese_candidates.jsonl picks_ja.jsonl > edits_ja.json
        picks file: one line per card you looked at, {"sheet": 0, "row": 1, "pick": 3}
        (pick null = no good picture). Prints the card_edit payloads, in batches of at most 60.

Needs Pillow (`pip install pillow`) and the DejaVu font, or edit FONT below.
"""
import json, os, sys, time, urllib.request, urllib.error
from PIL import Image, ImageDraw, ImageFont

UA = "LoopkyDeckBuilder/1.0"
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
CELL, ROWS, COLS, LABEL_W = 200, 6, 4, 260


def load(path):
    return [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()]


def fetch(url, cache):
    path = os.path.join(cache, url.rsplit("/", 1)[-1][-120:])
    if not os.path.exists(path):
        for _ in range(6):
            try:
                with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=30) as r:
                    open(path, "wb").write(r.read())
                break
            except urllib.error.HTTPError as e:
                if e.code != 429:
                    return None
                time.sleep(int(e.headers.get("Retry-After", 5)) + 1)
            except Exception:
                time.sleep(2)
        time.sleep(0.3)
    try:
        return Image.open(path).convert("RGB")
    except Exception:
        return None


def sheets(cands_path, outdir):
    items = load(cands_path)
    os.makedirs(outdir, exist_ok=True)
    cache = os.path.join(outdir, "cache")
    os.makedirs(cache, exist_ok=True)
    font, big = ImageFont.truetype(FONT, 15), ImageFont.truetype(FONT, 22)
    for s in range(0, len(items), ROWS):
        chunk = items[s:s + ROWS]
        img = Image.new("RGB", (LABEL_W + COLS * CELL, len(chunk) * CELL), "white")
        d = ImageDraw.Draw(img)
        for r, it in enumerate(chunk):
            y = r * CELL
            d.text((8, y + 6), f"ROW {r + 1}", fill="black", font=big)
            words, line, lines = (it["front"] + " [" + it["query"] + "]").split(), "", []
            for w in words:
                if len(line) + len(w) > 24:
                    lines.append(line); line = w
                else:
                    line = (line + " " + w).strip()
            lines.append(line)
            for i, t in enumerate(lines[:7]):
                d.text((8, y + 40 + i * 19), t, fill="black", font=font)
            for c, cand in enumerate(it["candidates"][:COLS]):
                x = LABEL_W + c * CELL
                pic = fetch(cand["url"], cache)
                if pic:
                    pic.thumbnail((CELL - 8, CELL - 8))
                    img.paste(pic, (x + 4, y + 4))
                d.rectangle([x, y, x + 34, y + 30], fill="yellow")
                d.text((x + 8, y + 2), str(c + 1), fill="black", font=big)
            d.line([0, y + CELL - 1, img.width, y + CELL - 1], fill="gray")
        img.save(os.path.join(outdir, f"sheet_{s // ROWS:03d}.jpg"), quality=80)
        print("sheet", s // ROWS, flush=True)


def edits(cands_path, picks_path):
    items = load(cands_path)
    out = []
    for p in load(picks_path):
        if not p.get("pick"):
            continue
        it = items[p["sheet"] * ROWS + p["row"] - 1]
        out.append({"id": it["card_id"], "front_image_url": it["candidates"][p["pick"] - 1]["url"]})
    batches = [out[i:i + 60] for i in range(0, len(out), 60)]
    print(json.dumps({"deck_id": items[0]["deck_id"], "pictures": len(out), "batches": batches}, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    {"sheets": sheets, "edits": edits}[sys.argv[1]](*sys.argv[2:])
