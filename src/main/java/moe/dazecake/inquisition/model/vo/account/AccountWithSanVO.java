package moe.dazecake.inquisition.model.vo.account;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import moe.dazecake.inquisition.model.entity.AccountEntity;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class AccountWithSanVO extends AccountEntity {
    private String san;
    private Integer todayLoginCount = 0;
    private String dispatchMode = "AUTO";
    @JsonFormat(pattern = "HH:mm")
    private LocalTime scheduleTime;
    @JsonFormat(pattern = "HH:mm")
    private List<LocalTime> scheduleTimes = new ArrayList<>();
    private LocalDateTime nextScheduledAt;
    private String scheduleStatus;
}
