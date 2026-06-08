package moe.dazecake.inquisition.model.vo.account;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "账号临时冷却信息")
public class AccountCooldownVO {
    @Schema(description = "账号ID")
    private Long id;

    @Schema(description = "账号名称")
    private String name;

    @Schema(description = "账号")
    private String account;

    @Schema(description = "冷却截止时间")
    private LocalDateTime until;

    @Schema(description = "冷却原因代码")
    private String reason;

    @Schema(description = "冷却原因说明")
    private String message;
}
