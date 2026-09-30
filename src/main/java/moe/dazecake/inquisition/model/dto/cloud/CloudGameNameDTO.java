package moe.dazecake.inquisition.model.dto.cloud;

import lombok.Data;

@Data
public class CloudGameNameDTO {
    private String deviceToken;
    private String taskId;
    private String attemptId;
    private String leaseId;
    private Long accountId;
    private String gameName;
    private String server;
}
