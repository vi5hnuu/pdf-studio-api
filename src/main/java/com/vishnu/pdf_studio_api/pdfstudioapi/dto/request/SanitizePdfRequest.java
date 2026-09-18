package com.vishnu.pdf_studio_api.pdfstudioapi.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What to strip when sanitizing.
 *
 * <p>Every flag defaults to the behaviour sanitize already had, so a caller that sends no body —
 * which is every existing caller — gets exactly the same document as before. The options exist
 * because "sanitize" was all-or-nothing: someone who only wanted the author's name off a CV also
 * lost the form they had filled in.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class SanitizePdfRequest {
    /** Document-level JavaScript and the /Names tree that carries it. */
    private boolean removeJavaScript = true;
    /** Attachments carried inside the PDF. */
    private boolean removeEmbeddedFiles = true;
    /** /OpenAction and /AA — anything that runs on open or on an event. */
    private boolean removeActions = true;
    /** Document information and the XMP metadata stream. */
    private boolean removeMetadata = true;
    /**
     * Annotations, including links. Off by default: unlike the rest, removing these visibly
     * changes the document — comments and clickable links disappear.
     */
    private boolean removeAnnotations = false;
    /** Turns every external /URI link into a plain, non-clickable annotation-free area. */
    private boolean removeExternalLinks = false;
    /** Interactive form fields. Off by default for the same reason as annotations. */
    private boolean removeForms = false;
}
