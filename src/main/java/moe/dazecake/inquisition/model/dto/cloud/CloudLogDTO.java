package moe.dazecake.inquisition.model.dto.cloud;

import lombok.Data;

@Data
public class CloudLogDTO {
    private String deviceToken;
    private String taskId;
    private String attemptId;
    private String leaseId;
    private Integer sequenceNo;
    private String level;
    private String phase;
    private String message;
    private String contextJson;
}
