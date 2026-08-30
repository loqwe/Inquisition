package moe.dazecake.inquisition.model.dto.cloud;

import lombok.Data;

@Data
public class CloudHeartbeatDTO {
    private String deviceToken;
    private Integer status;
    private String taskId;
    private String attemptId;
    private String leaseId;
}
