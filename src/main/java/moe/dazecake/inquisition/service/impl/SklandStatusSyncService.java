package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.mapper.SklandCredentialMapper;
import moe.dazecake.inquisition.model.dto.skland.SklandStatusSyncDTO;
import moe.dazecake.inquisition.model.entity.SklandCredentialEntity;
import moe.dazecake.inquisition.utils.GameDayClock;
import moe.dazecake.inquisition.utils.Result;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.LocalDateTime;

@Service
public class SklandStatusSyncService {

    @Resource
    AccountMapper accountMapper;

    @Resource
    SklandCredentialMapper credentialMapper;

    @Resource
    SklandCredentialService credentialService;

    @Resource
    AccountRuntimeService accountRuntimeService;

    public Result<String> sync(SklandStatusSyncDTO dto) {
        if (dto == null || blank(dto.getUid()) || dto.getCurrentSanity() == null
                || dto.getMaxSanity() == null || dto.getMaxSanity() <= 0) {
            return Result.paramError("uid和理智数据不能为空");
        }

        var accountId = dto.getAccountId();
        if (accountId == null) {
            var mapping = credentialMapper.selectOne(
                    Wrappers.<SklandCredentialEntity>lambdaQuery()
                            .eq(SklandCredentialEntity::getUid, dto.getUid())
                            .last("LIMIT 1"));
            accountId = mapping == null ? null : mapping.getAccountId();
        }
        if (accountId == null) {
            return Result.notFound("森空岛uid尚未绑定审判庭账号");
        }

        var account = accountMapper.selectById(accountId);
        if (account == null || Integer.valueOf(1).equals(account.getDelete())) {
            return Result.notFound("审判庭账号不存在");
        }

        persistUidMapping(accountId, dto);
        var observedAt = fromEpoch(dto.getObservedAt(), GameDayClock.now());
        var lastOnlineAt = fromEpoch(dto.getLastOnlineTs(), null);
        accountRuntimeService.recordSklandSnapshot(accountId, dto.getCurrentSanity(), dto.getMaxSanity(),
                valueOrZero(dto.getCompleteRecoveryTime()), lastOnlineAt, observedAt);
        return Result.success("森空岛状态同步成功");
    }

    private void persistUidMapping(Long accountId, SklandStatusSyncDTO dto) {
        var credential = credentialMapper.selectById(accountId);
        if (credential == null) {
            credential = new SklandCredentialEntity().setAccountId(accountId);
        }
        credential.setUid(dto.getUid());
        if (!blank(dto.getChannelMasterId())) {
            credential.setChannelMasterId(dto.getChannelMasterId());
        }
        credentialService.save(credential);
    }

    private LocalDateTime fromEpoch(Long epochSeconds, LocalDateTime fallback) {
        return epochSeconds == null || epochSeconds <= 0
                ? fallback
                : LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), GameDayClock.ZONE_ID);
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
