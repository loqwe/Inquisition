package moe.dazecake.inquisition.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Accessors(chain = true)
@TableName("skland_credential")
public class SklandCredentialEntity {
    @TableId(type = IdType.INPUT)
    private Long accountId;
    private String accessToken;
    private String cred;
    private String credToken;
    private String uid;
    private String channelMasterId;
    private LocalDateTime lastRefreshAt;
    private String lastError;
    private LocalDateTime updatedAt;
}
