package moe.dazecake.inquisition.model.dto.cloud;

import lombok.Data;

@Data
public class CloudTaskReportDTO {
    private String deviceToken;
    private String taskId;
    private String attemptId;
    private String leaseId;
    private String errorCode;
    private String errorMessage;
}
