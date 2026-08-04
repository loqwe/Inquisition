package moe.dazecake.inquisition.model.dto.account;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalTime;
import java.util.List;

@Data
public class AccountDispatchConfigDTO {
    private String dispatchMode;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime scheduleTime;

    @JsonFormat(pattern = "HH:mm")
    private List<LocalTime> scheduleTimes;
}
