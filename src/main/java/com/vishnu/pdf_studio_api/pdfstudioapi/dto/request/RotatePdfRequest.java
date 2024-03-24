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
    private Integer angle;//any file,any page rotate this angle [priority 3]
    private List<Integer> fileAngle; // any page in file rotate at angle [priority 2][if not empty && if any file angle is null the global angle is used]
    private Map<String,List<Integer>> pageAngles; // file-> page angle [priority 1] [if for a file list is null or empty -> fileAngle is used -> file angle null -> global angle is used,if list is not empty and particular page angle for that file is null -> file angle is used -> global angle]
    private Map<String,String> pageNos;//fileName->(null means rotate all) [format "0,1,2,3,4..."]
    private Boolean maintainRatio;//default true
    /*
    * see code at pdfTools
    * */
    RotatePdfRequest(String outFileName,Integer angle,List<Integer> fileAngle,Map<String,List<Integer>> pageAngles,Map<String,String> pageNos,Boolean maintainRatio){
        this.outFileName=outFileName;
        this.angle=angle;
        this.fileAngle=fileAngle!=null ? fileAngle : List.of();
        this.pageAngles = pageAngles!=null ? pageAngles : new HashMap<>();
        this.pageNos=pageNos!=null?pageNos:new HashMap<>();
        this.maintainRatio=maintainRatio!=null ? maintainRatio : true;
    }
}
