package moe.dazecake.inquisition.model.vo.task;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class TaskBoardAccountVO {
    private Long id;
    private String name;
    private String account;
    private String taskType;
    private Long agent;
    private LocalDateTime expireTime;
    private Boolean returnedFromUrgent;
}
