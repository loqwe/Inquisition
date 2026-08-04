package moe.dazecake.inquisition.service.impl;

import lombok.extern.slf4j.Slf4j;
import moe.dazecake.inquisition.mapper.SklandCredentialMapper;
import moe.dazecake.inquisition.model.entity.SklandCredentialEntity;
import moe.dazecake.inquisition.utils.GameDayClock;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class SklandCredentialService {

    @Resource
    SklandCredentialMapper credentialMapper;

    @Resource
    SklandClient sklandClient;

    public Optional<SklandCredentialEntity> ensureCredential(Long accountId) {
        var credential = credentialMapper.selectById(accountId);
        if (credential == null || isComplete(credential)) {
            return Optional.ofNullable(credential);
        }
        if (blank(credential.getAccessToken())) {
            return Optional.empty();
        }
        try {
            var generated = sklandClient.generateCredential(credential.getAccessToken());
            credential.setCred(generated.getCred())
                    .setCredToken(generated.getCredToken())
                    .setLastError(null)
                    .setLastRefreshAt(GameDayClock.now())
                    .setUpdatedAt(GameDayClock.now());
            if (blank(credential.getUid())) {
                var bindings = sklandClient.getBindings(credential);
                chooseBinding(credential, bindings);
            }
            credentialMapper.updateById(credential);
            return isComplete(credential) ? Optional.of(credential) : Optional.empty();
        } catch (Exception exception) {
            credential.setLastError(trimError(exception)).setUpdatedAt(GameDayClock.now());
            credentialMapper.updateById(credential);
            log.warn("森空岛凭据自动刷新失败，账号 {}", accountId, exception);
            return Optional.empty();
        }
    }

    public void save(SklandCredentialEntity credential) {
        if (credential == null || credential.getAccountId() == null) {
            throw new IllegalArgumentException("accountId is required");
        }
        credential.setUpdatedAt(GameDayClock.now());
        if (credentialMapper.selectById(credential.getAccountId()) == null) {
            credentialMapper.insert(credential);
        } else {
            credentialMapper.updateById(credential);
        }
    }

    public void clear(Long accountId) {
        if (accountId != null) {
            credentialMapper.deleteById(accountId);
        }
    }

    public Optional<SklandCredentialEntity> find(Long accountId) {
        return Optional.ofNullable(accountId == null ? null : credentialMapper.selectById(accountId));
    }

    private void chooseBinding(SklandCredentialEntity credential, List<SklandClient.Binding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return;
        }
        var selected = bindings.stream()
                .filter(binding -> binding.getGameName() == null || binding.getGameName().contains("明日方舟"))
                .filter(binding -> binding.isDefaultRole())
                .findFirst()
                .orElseGet(() -> bindings.stream()
                        .filter(binding -> binding.getGameName() == null || binding.getGameName().contains("明日方舟"))
                        .findFirst().orElse(bindings.get(0)));
        credential.setUid(selected.getUid()).setChannelMasterId(selected.getChannelMasterId());
    }

    private boolean isComplete(SklandCredentialEntity credential) {
        return credential != null && !blank(credential.getCred())
                && !blank(credential.getCredToken()) && !blank(credential.getUid());
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String trimError(Exception exception) {
        var message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 240 ? message.substring(0, 240) : message;
    }
}
