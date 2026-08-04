package moe.dazecake.inquisition.model.dto.skland;

import lombok.Data;

@Data
public class SklandCredentialDTO {
    private Long accountId;
    private String accessToken;
    private String cred;
    private String credToken;
    private String uid;
    private String channelMasterId;
}
