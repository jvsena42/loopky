---
type: llm
weight: 2
---

Grade where the response takes its pictures from and how it handles their licences. It PASSES when all of these hold:

1. The flag pictures come from `flagcdn.com` as PNGs (e.g. `https://flagcdn.com/w640/br.png`), or from Wikimedia Commons as a PNG thumbnail render of the SVG. A raw `.svg` URL anywhere fails.
2. The Monet pictures come from a public-domain museum source (the Art Institute of Chicago's IIIF URL or the Met's `primaryImageSmall`, checked for `is_public_domain`/`isPublicDomain`) or from Wikimedia Commons with a licence check.
3. Any `upload.wikimedia.org` URL is under `/wikipedia/commons/`, and any thumbnail width in it is one of 120, 250, 330, 500, 960, 1280 or 1920.
4. It does not take a URL from a search engine's image results, Pinterest, Instagram or Pixabay.
5. It credits in the deck description any picture whose licence needs attribution (CC BY or CC BY-SA), or says that every picture it chose is public domain or CC0 and so needs no credit.
6. The picture is on the asking side: the flag or painting on the front, the country or title on the back.
