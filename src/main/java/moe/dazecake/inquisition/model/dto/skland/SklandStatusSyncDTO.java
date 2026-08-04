package moe.dazecake.inquisition.model.dto.skland;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class SklandStatusSyncDTO {
    private Long accountId;
    private String uid;
    private String channelMasterId;
    private Integer currentSanity;
    private Integer maxSanity;
    private Long completeRecoveryTime;
    private Long lastOnlineTs;
    private Long observedAt;
}
