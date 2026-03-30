package moe.dazecake.inquisition.model.entity.ConfigEntitySet;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailyPlanNode {

    private String type = "fight";
    private Fight fight = new Fight("", 1);
    private LoopGroup loopGroup = new LoopGroup();

}
