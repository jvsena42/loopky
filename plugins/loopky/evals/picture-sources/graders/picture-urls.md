---
type: llm
weight: 2
---

Grade the two cards files in the response. It PASSES when all of these hold:

1. Every image URL is `https` and ends in a raster format (`.png`, `.jpg`, `.jpeg`, `.webp`, or a query string on an image CDN such as `?w=1080&fm=jpg`). No image URL ends in `.svg`, `.tif`, `.tiff`, `.webm` or `.stl`.
2. The flag pictures come from a source that serves flags as raster images: `flagcdn.com/w<width>/<code>.png`, or a Wikimedia `/thumb/…/<width>px-….svg.png` render whose width is one of 120, 250, 330, 500, 960, 1280 or 1920.
3. The painting pictures come from a museum open-access source or Wikimedia Commons (for example `images.metmuseum.org`, `www.artic.edu/iiif/…`, or `upload.wikimedia.org`) — not from a web page URL, a search-results URL, Pinterest, Google Images or Pixabay.
4. In both files the picture is on the front (the asking side) and the name is on the back.

Extra correct steps do **not** count against the response: mentioning `--dry-run`, `--check-images`, `loopky doctor`, login, licences, or that the URLs still need checking. FAIL only when one of the four points is missing or contradicted. Do not fail a response because a specific URL might not exist — this grades the shape and source of the URLs, not whether they resolve.
