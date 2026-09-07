# Tool capability matrix

What each tool can actually do, and where that capability is reachable from. Compiled by walking
`dto/request/*.java` and `controllers/PdfController.java` against the web routes under
`app/tool/*/page.tsx` (pdf-studio) and `lib/tools/tool_registry.dart` (pdf-craft).

It exists because capability drifts between the three repos in both directions: `mirror-pdf` has
accepted a `pages` list server-side for some time and **no web page has ever sent one**, while
`stamp-pdf` carries a client-side comment about a server limitation. A table beats re-deriving that
from three codebases every time.

Legend: **Y** yes · **N** no · **n/a** the question does not apply to this tool ·
**srv** supported by the API but not reachable from that client.

## Columns

| Column | Question |
|---|---|
| Page range | Can the caller restrict the operation to some pages? |
| Image in | Does it accept an image (not only a PDF) where an image is the natural input? |
| Placeable | Can the caller choose where on the page the output lands? |
| Aspect safe | Is an image drawn without distortion? |
| Web / App | Is the tool reachable from that client? |

## Page-shaping tools

| Tool | Page range | Image in | Placeable | Aspect safe | Web | App |
|---|---|---|---|---|---|---|
| stamp-pdf | Y (`from/toPage`) | Y | Y | Y | Y | Y |
| place-image | Y (`pages`) | Y | Y | Y | Y | Y (`image-overlay`) |
| watermark-pdf | Y (`from/toPage`) | n/a (text only) | Y (9-point) | n/a | Y | Y |
| header-footer | Y (`from/toPage`) | n/a | Y (padding) | n/a | Y | Y |
| page-numbers | Y (`from/toPage`) | n/a | Y (9-point) | n/a | Y | Y |
| redact-pdf | Y (per region) | n/a | Y | n/a | Y | Y |
| mirror-pdf | Y (`pages`) | n/a | n/a | n/a | Y | Y |
| rotate-pdf | Y (`pageAngles`) | n/a | n/a | n/a | Y | Y |
| crop-pdf | Y (`pages`) | n/a | n/a | n/a | Y | Y |
| grayscale-pdf | Y (`pages`) | n/a | n/a | n/a | Y | Y |
| scale-pdf | Y (`pages`) | n/a | n/a | n/a | Y | Y |
| resize-page | Y (`pages`) | n/a | n/a | n/a | Y | Y |
| pdf-to-jpg | Y (`pages`) | n/a | n/a | Y | **srv** | **srv** |
| extract-text | Y (`pages`) | n/a | n/a | n/a | **srv** | **srv** |
| image-to-pdf | n/a | Y | Y (page size) | Y | Y | Y |

Whole-document by nature, no range meaningful: merge, split, split-by-size, reorder, insert,
replace-pages, add-blank-pages, duplicate-pages, remove-blank-pages, compress, optimize, repair,
flatten, sanitize, protect, unprotect, n-up, edit/remove-metadata, bookmarks, forms, analyze,
extract-images, extract-fonts, extract-embedded-files, pdf-to-word/excel/pptx.

## What changed

All seven defects this table was built to record are fixed:

1. **stamp-pdf takes an image or a PDF.** `UploadValidator.pdfOrImage` classifies the artwork by
   magic bytes and `ArtworkKind` carries the answer to the tool.
2. **stamp-pdf can be positioned and sized**, through the shared `Placement`.
3. **place-image spans a page range**, embedding the image once however many pages use it.
4. **Nothing stretches by default.** `ImageFit.CONTAIN` fits artwork inside the requested box;
   `STRETCH` remains for the app's deliberate "Free" resize mode.
5. **image-to-pdf produces a real page size** — A4, auto orientation, optional margin — with
   `ImagePageSize.MATCH_IMAGE` preserving the old one-point-per-pixel behaviour.
6. **crop, grayscale, scale and resize-page take a page range**, and mirror's reaches both
   clients. Greyscale carries unselected pages through as vector rather than re-rendering them.
7. **pdf-to-jpg and extract-text take one too** — the two costliest tools to run over a whole
   document when one page was wanted.

Beyond the table: a finished result can be carried into the next tool on both clients, each
tool remembers what it was last set to, and the web has batch processing to match the app's.

## Remaining client gaps

`pdf-to-jpg` and `extract-text` accept a page range that neither client sends yet — the same
drift `mirror-pdf` was in when this document was written. Both clients have the control to do it
(`PageRangeField` on the web, `PageRangeSelector` in the app); only the wiring is missing.

Present in the app, absent from the web: `sign`, `qr-stamp`, `annotate`, `compare`, `organize`,
`extract-pages`, `reverse-pages`, `pdf-info`, `fill-form`.

Web batch covers six parameter-free tools, matching the app's `BatchProcessView`.

`resize-image` already handled aspect correctly via `maintainAspectRatio` (`ImageService`), which
is the shape the other image paths now follow.
