package com.vishnu.pdf_studio_api.pdfstudioapi.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.vishnu.pdf_studio_api.pdfstudioapi.enums.UserAccessPermission;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Set;

@Getter
@Setter
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ProtectPdfRequest {
    private final String outFileName;
    @NotBlank(message = "ownerPassword is required") private final String ownerPassword;
    @NotBlank(message = "userPassword is required") private final String userPassword;
    private final Set<UserAccessPermission> userAccessPermissions;//empty means user has owner permission

    ProtectPdfRequest(String outFileName,String ownerPassword,String userPassword,Set<UserAccessPermission> userAccessPermissions){
        this.outFileName=outFileName;
        this.ownerPassword=ownerPassword;
        this.userPassword=userPassword;
        this.userAccessPermissions=userAccessPermissions!=null ? userAccessPermissions : Set.of();
    }
}
