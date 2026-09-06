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
| stamp-pdf | Y (`from/toPage`) | **N** | **N** | n/a | Y | Y |
| place-image | **N** (single `page`) | Y | Y | **N** | Y | Y (`image-overlay`) |
| watermark-pdf | Y (`from/toPage`) | n/a (text only) | Y (9-point) | n/a | Y | Y |
| header-footer | Y (`from/toPage`) | n/a | Y (padding) | n/a | Y | Y |
| page-numbers | Y (`from/toPage`) | n/a | Y (9-point) | n/a | Y | Y |
| redact-pdf | Y (per region) | n/a | Y | n/a | Y | Y |
| mirror-pdf | Y (`pages`) | n/a | n/a | n/a | **srv** | Y |
| rotate-pdf | Y (`pageAngles`) | n/a | n/a | n/a | Y | Y |
| crop-pdf | **N** | n/a | n/a | n/a | Y | Y |
| grayscale-pdf | **N** | n/a | n/a | n/a | Y | Y |
| scale-pdf | **N** | n/a | n/a | n/a | Y | Y |
| resize-page | **N** | n/a | n/a | n/a | Y | Y |
| pdf-to-jpg | **N** | n/a | n/a | Y | Y | Y |
| extract-text | **N** | n/a | n/a | n/a | Y | Y |
| image-to-pdf | n/a | Y | N | **N** | Y | Y |

Whole-document by nature, no range meaningful: merge, split, split-by-size, reorder, insert,
replace-pages, add-blank-pages, duplicate-pages, remove-blank-pages, compress, optimize, repair,
flatten, sanitize, protect, unprotect, n-up, edit/remove-metadata, bookmarks, forms, analyze,
extract-images, extract-fonts, extract-embedded-files, pdf-to-word/excel/pptx.

## The seven confirmed defects

1. **stamp-pdf takes only a PDF.** `PdfService.stampPdf` opens the part as
   `TempFiles.of(stampFile, ".pdf")`; `StampPdfView.dart` restricts the picker to `['pdf']`. A PNG
   logo or a signature cannot be stamped at all.
2. **stamp-pdf cannot be positioned or sized.** `PdfTools.stampPdf` calls `cs.drawForm(stampForm)`
   with no transformation matrix.
3. **place-image is single-page.** `PlaceImageRequest.page` is one `int`, so `SignPdfView` — which
   routes a drawn signature into `PlaceImageView` — can only sign one page per run.
4. **place-image distorts.** `cs.drawImage(pdImage, x, y, w, h)` sizes purely from the requested
   box. The app's aspect lock hides this most of the time; nothing else does.
5. **image-to-pdf produces unusable pages.** `PdfTools.imagesToPdf` builds
   `new PDRectangle(width, height)` from **pixels**, so a 4000x3000 photo becomes a 4000x3000
   **point** page (~55x42 inches), and mixed inputs give mixed page sizes.
6. **Four tools silently rewrite every page** (crop, grayscale, scale, resize-page), and mirror's
   range never reaches the browser.
7. **Two more would benefit from a range**, found while compiling this table and not in the original
   scope: `pdf-to-jpg` rasterises all 200 pages when you wanted page 3, and `extract-text` has the
   same shape. Both are among the costliest operations per run, so a range saves time and credits.

## Client coverage gaps

Present in the app, absent from the web: `sign`, `qr-stamp`, `annotate`, `compare`, `organize`,
`extract-pages`, `reverse-pages`, `pdf-info`, `fill-form`, `batch`.

Present on the web, absent from the app: none — the app is a superset.

`resize-image` already handles aspect correctly via `maintainAspectRatio`
(`ImageService`), which is the shape the other image paths should follow.
