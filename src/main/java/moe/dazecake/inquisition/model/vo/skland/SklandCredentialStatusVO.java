package moe.dazecake.inquisition.model.vo.skland;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class SklandCredentialStatusVO {
    private Long accountId;
    private boolean configured;
    private boolean autoRefreshEnabled;
    private String uid;
    private String channelMasterId;
    private LocalDateTime lastRefreshAt;
    private LocalDateTime updatedAt;
    private String lastError;
}
