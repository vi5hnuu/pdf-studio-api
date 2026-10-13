package com.vishnu.pdf_studio_api.pdfstudioapi.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;


@Getter
public class Padding {
    private final Float top;
    private final Float left;
    private final Float bottom;
    private final Float right;

    /**
     * Jackson had no way into this class: the fields are final, Lombok generated only getters,
     * and a public no-arg constructor existed — which Jackson prefers, leaving it with nothing to
     * write the values into. So the padding a caller sent was silently dropped and every
     * document came out with zero padding, with a 200 and no indication anything was ignored
     * (unknown-property failure is off, so there was nothing to notice).
     *
     * <p>Annotating this constructor keeps the fields final rather than opening them up with
     * setters.
     */
    @JsonCreator
    public Padding(@JsonProperty("top") Float top,
                   @JsonProperty("left") Float left,
                   @JsonProperty("bottom") Float bottom,
                   @JsonProperty("right") Float right) {
        this.top = top != null ? top : 0;
        this.left = left != null ? left : 0;
        this.bottom = bottom != null ? bottom : 0;
        this.right = right != null ? right : 0;
    }

    public Padding() {
        this.top = 0f;
        this.left = 0f;
        this.bottom = 0f;
        this.right = 0f;
    }
}
