package com.vishnu.pdf_studio_api.pdfstudioapi.web;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Posts the exact JSON the web tool pages send, to each endpoint they target.
 *
 * <p>The web tools are generated from a declaration, so a field named wrongly there would
 * be silently ignored by Jackson and the tool would quietly run with defaults instead of
 * the user's settings — no error, just the wrong output. These pin the field names.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebPayloadContractTest {

    @Autowired MockMvc mockMvc;

    private byte[] pdf(int pages) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    private RequestPostProcessor user() {
        return jwt().jwt(b -> b.subject(UUID.randomUUID().toString()).claim("roles", List.of("ROLE_USER")))
                .authorities((org.springframework.security.core.GrantedAuthority)
                        new SimpleGrantedAuthority("ROLE_USER"));
    }

    private MockMultipartFile part(String name, String json) {
        return new MockMultipartFile(name, "", "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private MockMultipartFile file(String field, byte[] content) throws Exception {
        return new MockMultipartFile(field, "a.pdf", "application/pdf", content);
    }

    /** Runs one endpoint with the web's payload and asserts it produced a file. */
    private void assertAccepts(String path, String infoPart, String json, MockMultipartFile... extra)
            throws Exception {
        var request = multipart("/api/v1/pdf-studio/" + path).file(file("file", pdf(4)));
        if (infoPart != null) request = request.file(part(infoPart, json));
        for (MockMultipartFile f : extra) request = request.file(f);
        // Idempotency-Key is required by @ChargeCredits endpoints — the ledger's
        // uq(user_id, idempotency_key) does not dedupe NULLs under MySQL semantics.
        request.header("Idempotency-Key", "test-" + java.util.UUID.randomUUID());

        MvcResult result = mockMvc.perform(request.with(user()).with(fromUniqueIp())).andReturn();
        assertEquals(200, result.getResponse().getStatus(),
                path + " rejected the web payload: " + result.getResponse().getContentAsString());
        assertTrue(result.getResponse().getContentAsByteArray().length > 0, path + " returned nothing");
    }

    /**
     * Every mock request otherwise arrives from 127.0.0.1, and welcome credits are rationed
     * per IP per day — so past the fifth test user the account opens with a zero balance and
     * the tool returns 402. Giving each user its own address keeps the guard's real behaviour
     * intact (it is covered by IpGrantGuardTest) without it capping the suite.
     */
    private static final java.util.concurrent.atomic.AtomicInteger _uniqueIpCounter =
            new java.util.concurrent.atomic.AtomicInteger();

    private static RequestPostProcessor fromUniqueIp() {
        int n = _uniqueIpCounter.incrementAndGet();
        return request -> {
            request.setRemoteAddr("198.51.100." + (n % 250));
            return request;
        };
    }

    @Test
    @DisplayName("mirror-pdf accepts the direction the web sends")
    void mirror() throws Exception {
        assertAccepts("mirror-pdf", "mirror-pdf-info", "{\"direction\":\"VERTICAL\"}");
    }

    @Test
    @DisplayName("resize-page accepts the size preset the web sends")
    void resizePage() throws Exception {
        assertAccepts("resize-page", "resize-page-info", "{\"size\":\"LETTER\"}");
    }

    @Test
    @DisplayName("scale-pdf accepts the scale factor the web sends")
    void scale() throws Exception {
        assertAccepts("scale-pdf", "scale-pdf-info", "{\"scale\":0.5}");
    }

    @Test
    @DisplayName("split-by-size accepts max_size_mb and out_file_name")
    void splitBySize() throws Exception {
        assertAccepts("split-by-size", "split-by-size-info",
                "{\"max_size_mb\":1,\"out_file_name\":\"parts\"}");
    }

    @Test
    @DisplayName("insert-pdf accepts after_page and the second document")
    void insertPdf() throws Exception {
        assertAccepts("insert-pdf", "insert-pdf-info",
                "{\"after_page\":1,\"out_file_name\":\"merged\"}",
                new MockMultipartFile("insert", "b.pdf", "application/pdf", pdf(2)));
    }

    @Test
    @DisplayName("replace-pages accepts from, to and the replacement document")
    void replacePages() throws Exception {
        assertAccepts("replace-pages", "replace-pages-info",
                "{\"from\":1,\"to\":2,\"out_file_name\":\"replaced\"}",
                new MockMultipartFile("replacement", "b.pdf", "application/pdf", pdf(2)));
    }

    @Test
    @DisplayName("pdf-to-jpg accepts the quality as a name and as a DPI number")
    void pdfToJpgQualityForms() throws Exception {
        // The DPI form is what every shipped app build sends. Jackson reads an integer as an
        // enum *ordinal*, so 72/150/300 were all out of range and every pdf-to-jpg request was
        // a 400 before the controller body ran — which is why the tool failed identically in
        // zip, horizontal and vertical mode: it never looked at single or direction.
        for (String quality : new String[]{"\"LOW\"", "\"low\"", "\"HIGH\"", "72", "150", "300"}) {
            assertAccepts("pdf-to-jpg", "pdf-to-jpg-info",
                    "{\"out_file_name\":\"q\",\"quality\":" + quality + ",\"single\":false}");
        }
        // All three modes, now that quality no longer fails first.
        assertAccepts("pdf-to-jpg", "pdf-to-jpg-info",
                "{\"quality\":\"LOW\",\"single\":true,\"direction\":\"VERTICAL\",\"image_gap\":4}");
        assertAccepts("pdf-to-jpg", "pdf-to-jpg-info",
                "{\"quality\":\"LOW\",\"single\":true,\"direction\":\"HORIZONTAL\",\"image_gap\":0}");
    }

    @Test
    @DisplayName("page-numbers accepts the type as a name and as its label, and applies padding")
    void pageNumbersTypeFormsAndPadding() throws Exception {
        // The label form is what every shipped app build sends; Jackson matches constant names
        // case-sensitively, so "X" and "page_X_of_Y" were both a 400.
        for (String type : new String[]{"\"ONLY_X\"", "\"PAGE_X\"", "\"PAGE_X_OF_Y\"",
                                        "\"X\"", "\"page_X\"", "\"page_X_of_Y\""}) {
            assertAccepts("page-numbers", "page-numbers-info",
                    "{\"page_no_type\":" + type + ",\"size\":12}");
        }
        // Padding was silently dropped: final fields, no setters, and a no-arg constructor
        // Jackson preferred. It has to survive binding, not just be accepted.
        assertAccepts("page-numbers", "page-numbers-info",
                "{\"page_no_type\":\"ONLY_X\",\"padding\":{\"top\":5,\"left\":6,\"bottom\":7,\"right\":8}}");
    }

    @Test
    @DisplayName("protect-pdf accepts permissions as names and as PDF bits")
    void protectPermissionForms() throws Exception {
        // Bits 9-12 were out of the ordinal range and produced a 400; 0 and 3-6 were valid
        // indices and silently selected a *different* permission than the caller asked for.
        assertAccepts("protect-pdf", "protect-pdf-info",
                "{\"owner_password\":\"ownerpass11\",\"user_password\":\"userpass11\","
                        + "\"user_access_permissions\":[\"PRINT\",\"FILL_IN_FORM\",\"FAITHFUL_PRINT\"]}");
        assertAccepts("protect-pdf", "protect-pdf-info",
                "{\"owner_password\":\"ownerpass11\",\"user_password\":\"userpass11\","
                        + "\"user_access_permissions\":[3,9,12,0]}");
    }

    @Test
    @DisplayName("an unreadable field is named in the 400, with what it would accept")
    void malformedBodyNamesTheField() throws Exception {
        var request = multipart("/api/v1/pdf-studio/pdf-to-jpg")
                .file(file("file", pdf(2)))
                .file(part("pdf-to-jpg-info", "{\"quality\":\"ultra\"}"));
        MvcResult result = mockMvc.perform(request.with(user()).with(fromUniqueIp())).andReturn();

        assertEquals(400, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString();
        // The whole point: it used to say only "the request body could not be read", which hid
        // four tools failing every request on one wrong field.
        assertTrue(body.contains("quality"), "the 400 should name the field, was: " + body);
        assertTrue(body.contains("LOW"), "the 400 should list what it accepts, was: " + body);
    }

    @Test
    @DisplayName("a colour sent by the client still binds, now that ColorModel is immutable")
    void colorModelStillBinds() throws Exception {
        // ColorModel has no setters and its fields were made final when BLACK was corrected from
        // white, so it deserializes only through Jackson's implicit parameter-name creator —
        // which works because spring-boot-starter-parent compiles with -parameters. Worth pinning:
        // nothing else would notice it breaking except a document coming out the wrong colour.
        assertAccepts("page-numbers", "page-numbers-info",
                "{\"page_no_type\":\"ONLY_X\",\"fill_color\":{\"r\":255,\"g\":0,\"b\":0,\"a\":255}}");
        assertAccepts("watermark-pdf", "watermark-pdf-info",
                "{\"text\":\"DRAFT\",\"fill_color\":{\"r\":10,\"g\":20,\"b\":30,\"a\":200}}");
        // Omitted, so the default applies — which used to be white-on-white.
        assertAccepts("page-numbers", "page-numbers-info", "{\"page_no_type\":\"ONLY_X\"}");
    }

    @Test
    @DisplayName("reorder-pdf accepts a subset, a reversal and a repeat, and names a bad index")
    void reorderAcceptsWhatItsCallersSend() throws Exception {
        // Four screens reach this endpoint and only one of them sends a permutation of every
        // page: delete-pages sends every index except the removed ones, extract-pages sends only
        // the selected ones, reverse sends them backwards. A completeness check here rejected
        // delete and extract outright, which is why this pins the shapes rather than the idea.
        assertAccepts("reorder-pdf", "reorder-pdf-info", "{\"order\":[0,1,2,3]}");  // organize
        assertAccepts("reorder-pdf", "reorder-pdf-info", "{\"order\":[0,2]}");      // delete
        assertAccepts("reorder-pdf", "reorder-pdf-info", "{\"order\":[1]}");        // extract
        assertAccepts("reorder-pdf", "reorder-pdf-info", "{\"order\":[3,2,1,0]}");  // reverse
        assertAccepts("reorder-pdf", "reorder-pdf-info", "{\"order\":[0,0,1]}");    // repeat

        var request = multipart("/api/v1/pdf-studio/reorder-pdf")
                .file(file("file", pdf(4)))
                .file(part("reorder-pdf-info", "{\"order\":[99]}"));
        MvcResult result = mockMvc.perform(request.with(user()).with(fromUniqueIp())).andReturn();
        assertEquals(400, result.getResponse().getStatus());
        // An out-of-range index used to be a 500, because the page tree's
        // IndexOutOfBoundsException is not a cause the handler recognises.
        assertTrue(result.getResponse().getContentAsString().contains("99"),
                "the 400 should name the page that does not exist");
    }

    @Test
    @DisplayName("tools that take no options work with only the file part")
    void noOptionTools() throws Exception {
        assertAccepts("sanitize-pdf", null, null);
        assertAccepts("remove-metadata", null, null);
    }

    @Test
    @DisplayName("page-numbers works with the info part omitted entirely")
    void pageNumbersWithoutOptions() throws Exception {
        // The part is declared optional, and omitting it used to NullPointerException.
        assertAccepts("page-numbers", null, null);
    }

    @Test
    @DisplayName("image tools accept the fields the web sends")
    void imageTools() throws Exception {
        byte[] png = pngPixel();

        for (String[] tool : new String[][]{
                {"rotate-image", "rotate-image-info", "{\"angle\":180,\"out_file_name\":\"r\"}"},
                {"flip-image", "flip-image-info", "{\"direction\":\"VERTICAL\",\"out_file_name\":\"f\"}"},
                {"border-image", "border-image-info", "{\"width\":10,\"r\":255,\"g\":0,\"b\":0,\"out_file_name\":\"b\"}"},
        }) {
            MvcResult result = mockMvc.perform(multipart("/api/v1/image-studio/" + tool[0])
                    .file(new MockMultipartFile("file", "a.png", "image/png", png))
                    .file(part(tool[1], tool[2]))
                    .with(user()).with(fromUniqueIp())).andReturn();
            assertEquals(200, result.getResponse().getStatus(),
                    tool[0] + " rejected the web payload: " + result.getResponse().getContentAsString());
        }
    }

    /** Smallest valid PNG, so the image endpoints have something real to decode. */
    private byte[] pngPixel() throws Exception {
        var image = new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        }
    }
}
