package com.vishnu.pdf_studio_api.pdfstudioapi.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.Padding;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.PageNoType;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.Postion;
import com.vishnu.pdf_studio_api.pdfstudioapi.model.ColorModel;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class RotatePdfRequest {
    private String outFileName;
    private Integer fileAngle; // angle at which all pages will be rotated
    private Map<Integer,Integer> pageAngles; // if a page do not have angle file angle is used else no rotation [0 index]
    private Boolean maintainRatio;//default true
    RotatePdfRequest(String outFileName,Integer fileAngle,Map<Integer,Integer> pageAngles,Boolean maintainRatio){
        this.outFileName=outFileName;
        this.fileAngle=fileAngle!=null ? fileAngle : 0;
        this.pageAngles = pageAngles!=null ? pageAngles : new HashMap<>();
        this.maintainRatio=maintainRatio!=null ? maintainRatio : true;
    }
}
