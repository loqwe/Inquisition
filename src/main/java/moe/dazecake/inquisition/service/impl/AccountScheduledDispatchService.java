package moe.dazecake.inquisition.service.impl;

import lombok.extern.slf4j.Slf4j;
import moe.dazecake.inquisition.mapper.AccountDispatchConfigMapper;
import moe.dazecake.inquisition.model.entity.AccountScheduledRunEntity;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class AccountScheduledDispatchService {

    @Resource
    AccountDispatchConfigMapper configMapper;

    @Resource
    AccountScheduledRunService runService;

    @Resource
    AccountScheduledDispatchProcessor processor;

    public List<AccountScheduledRunEntity> scan(LocalDateTime now) {
        Objects.requireNonNull(now, "now");
        var dueConfigurations = configMapper.selectDue(now);
        var processed = 0;
        var failed = 0;
        if (dueConfigurations != null) {
            for (var candidate : dueConfigurations) {
                if (candidate == null || candidate.getAccountId() == null) {
                    continue;
                }
                try {
                    processor.process(candidate.getAccountId(), now);
                    processed++;
                } catch (RuntimeException exception) {
                    failed++;
                    log.warn("账号定时调度处理失败，账号 {}", candidate.getAccountId(), exception);
                }
            }
        }
        var dispatchable = restoreDispatchable(now);
        log.info("账号定时调度扫描完成: processed={}, failed={}, dispatchable={}",
                processed, failed, dispatchable.size());
        return dispatchable;
    }

    public List<AccountScheduledRunEntity> restoreDispatchable(LocalDateTime now) {
        Objects.requireNonNull(now, "now");
        var runs = runService.findDispatchable(now);
        return runs == null ? new ArrayList<>() : runs;
    }
}
