package moe.dazecake.inquisition.model.vo.task;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class TaskBoardSummaryVO {
    private Integer urgent;
    private Integer pending;
    private Integer inProgress;
    private Integer coolingDown;
    private Integer frozen;
}
