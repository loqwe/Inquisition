package moe.dazecake.inquisition.model.vo.task;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@AllArgsConstructor
public class ScheduledTaskOverviewVO {
    private final LocalDateTime serverTime;
    private final int totalCount;
    private final int healthyCount;
    private final int runningCount;
    private final int abnormalCount;
    private final int waitingCount;
    private final int disabledCount;
    private final List<ScheduledTaskStatusVO> tasks;
}
