package moe.dazecake.inquisition.model.vo.task;

import lombok.Data;
import lombok.experimental.Accessors;
import moe.dazecake.inquisition.model.vo.account.AccountCooldownVO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class TaskBoardVO {
    private LocalDateTime generatedAt;
    private TaskBoardSummaryVO summary;
    private List<UrgentTaskVO> urgentTasks = new ArrayList<>();
    private List<TaskBoardAccountVO> pendingTasks = new ArrayList<>();
    private List<RunningTaskVO> runningTasks = new ArrayList<>();
    private List<AccountCooldownVO> cooldownTasks = new ArrayList<>();
    private List<TaskBoardAccountVO> frozenTasks = new ArrayList<>();
}
