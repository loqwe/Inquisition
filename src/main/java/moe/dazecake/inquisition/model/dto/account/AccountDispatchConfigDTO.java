package moe.dazecake.inquisition.model.dto.account;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalTime;

@Data
public class AccountDispatchConfigDTO {
    private String dispatchMode;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime scheduleTime;
}
