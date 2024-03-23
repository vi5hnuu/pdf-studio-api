package com.vishnu.pdf_studio_api.pdfstudioapi.model;

import lombok.Getter;

import java.awt.*;


public class ColorModel {
    private int r;
    private int g;
    private int b;
    private Integer a;

    public static ColorModel BLACK=new ColorModel(255,255,255,0);

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
