package com.vishnu.pdf_studio_api.pdfstudioapi.model;

import lombok.Getter;

import java.awt.*;


public class ColorModel {
    private final int r;
    private final int g;
    private final int b;
    private final Integer a;

    /**
     * The default ink for text the caller did not colour: watermarks, page numbers and
     * headers/footers all fall back to this.
     *
     * <p>It used to be {@code (255,255,255,0)} — white. PDFBox's
     * {@code setNonStrokingColor(Color)} uses only the RGB components and discards the alpha, so
     * omitting the colour drew white text on white paper and returned 200: the tool reported
     * success and the output looked blank. Now final as well, since a reassignable public static
     * default is a global anything could move.
     */
    public static final ColorModel BLACK=new ColorModel(0,0,0,255);

    public ColorModel(int r,int g,int b,Integer a) {
        this.r=r;
        this.g=g;
        this.b=b;
        this.a=a!=null ? a : 0;
    }

    public Color color(){
        return new Color(r,g,b,a);
    }
}
