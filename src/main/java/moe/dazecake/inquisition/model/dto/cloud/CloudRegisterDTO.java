package moe.dazecake.inquisition.model.dto.cloud;

import lombok.Data;

@Data
public class CloudRegisterDTO {
    private String deviceName;
    private String deviceToken;
    private String clientVersion;
}
