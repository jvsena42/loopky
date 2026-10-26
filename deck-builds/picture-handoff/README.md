# Picture handoff: Japanese, Korean and Mandarin decks

Three decks are published on production and verified card by card:

| Deck | id | Cards | Photos now | Cards left to picture |
|---|---|---|---|---|
| Japonês: O deck definitivo para brasileiros | `japones-brasileiros` | 4,365 | 127 | 225 (`japanese_candidates.jsonl`) |
| Coreano: O deck definitivo para brasileiros | `coreano-brasileiros` | 4,481 | 0 | 412 (`korean_candidates.jsonl`) |
| Mandarim: O deck definitivo para brasileiros | `mandarim-brasileiros` | 4,704 | 0 | 401 (`mandarin_candidates.jsonl`) |

The session that built them could not finish the pictures: Wikimedia throttles thumbnail downloads
from its sandbox (~45 s per image). From a normal connection the work below takes about an hour.

## What is already done

Each line of a `*_candidates.jsonl` file is one card that should get a picture:
`deck_id`, `card_id` (the stored id), `front`, `back`, the English search `query`, and up to six
`candidates` (Wikimedia Commons files, best first). Every candidate came from a Commons search
filtered to **CC0 or public domain** by structured data, so no credit line is needed. Each `url`
is the 330 px thumbnail the phone will load.

## Steps

1. **Build contact sheets** (any machine with Python and Pillow):
   ```sh
   python3 pictures.py sheets japanese_candidates.jsonl sheets_ja/
   python3 pictures.py sheets korean_candidates.jsonl sheets_ko/
   python3 pictures.py sheets mandarin_candidates.jsonl sheets_zh/
   ```
2. **Pick by eye.** Open each sheet. For each row, choose the candidate that shows **exactly**
   the card's word, clearly, as a learner would recognise it, or none. About half the rows will
   have none, and that is fine: a near miss teaches the wrong thing. Reject:
   - a diagram, map, old engraving or painting when a photo of the thing exists
   - a single item for a plural word, or a part standing for the whole
   - brands, logos and text-heavy images
   - people when the word is not about people
   - nudity, gore, rude gestures, smoking, and alcohol unless the word is about it
   - any file whose name is inappropriate (the URL is public)

   Write `picks_ja.jsonl` / `picks_ko.jsonl` / `picks_zh.jsonl`, one line per row: `{"sheet": 0, "row": 1, "pick": 3}`,
   with `"pick": null` for none. (Claude can do this step: give it the sheets and these rules.)
3. **Make the edits:**
   ```sh
   python3 pictures.py edits japanese_candidates.jsonl picks_ja.jsonl > edits_ja.json
   python3 pictures.py edits korean_candidates.jsonl picks_ko.jsonl > edits_ko.json
   python3 pictures.py edits mandarin_candidates.jsonl picks_zh.jsonl > edits_zh.json
   ```
4. **Apply them through the Loopky connector** (or `loopky card edit`): one `card_edit` call per
   batch in the file, `deck_id` as given and `cards` = the batch. Batches are at most 60 cards
   because a larger edit can time out halfway. After any timeout, re-read with `card_list`
   before retrying.
5. **Update each description's photo count** with `deck_edit`, keeping the rest of the text as is:
   - Japanese: change `127 cartões com foto.` to the new total (127 + the Japanese picks).
   - Korean and Mandarin: add a line `N cartões com foto.` before the last line (`20 cartões por dia…`).
6. **Verify** with `card_list` (`has_image: true`) that the counts match.

This folder is a working handoff, not part of the app. Delete it once the pictures are in.
