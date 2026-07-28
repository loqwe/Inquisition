package moe.dazecake.inquisition.service.impl;

import lombok.extern.slf4j.Slf4j;
import moe.dazecake.inquisition.mapper.AccountDispatchConfigMapper;
import moe.dazecake.inquisition.model.entity.AccountScheduledRunEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
public class AccountScheduledDispatchService {
    public static final int DEFAULT_BATCH_SIZE = 200;

    @Value("${inquisition.accountSchedule.scanBatchSize:200}")
    int batchSize = DEFAULT_BATCH_SIZE;

    @Resource
    AccountDispatchConfigMapper configMapper;

    @Resource
    AccountScheduledRunService runService;

    @Resource
    AccountScheduledDispatchProcessor processor;

    public List<AccountScheduledRunEntity> scan(LocalDateTime now) {
        Objects.requireNonNull(now, "now");
        var limit = validatedBatchSize();
        var dueConfigurations = configMapper.selectDue(now, limit);
        var processed = 0;
        var failed = 0;
        if (dueConfigurations != null) {
            for (var index = 0; index < dueConfigurations.size() && index < limit; index++) {
                var candidate = dueConfigurations.get(index);
                if (candidate == null || candidate.getAccountId() == null) {
                    continue;
                }
                try {
                    processor.process(candidate.getAccountId(), now);
                    processed++;
                } catch (RuntimeException exception) {
                    failed++;
                    log.warn("账号定时调度处理失败，账号 {}, errorType={}",
                            candidate.getAccountId(), exception.getClass().getSimpleName());
                }
            }
        }
        if (failed > 0) {
            log.warn("账号定时调度批次部分失败: processed={}, failed={}", processed, failed);
            throw new PartialScheduledDispatchException(failed);
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

    private int validatedBatchSize() {
        if (batchSize <= 0) {
            throw new IllegalStateException("Scheduled account dispatch batch size must be positive");
        }
        return batchSize;
    }
}
