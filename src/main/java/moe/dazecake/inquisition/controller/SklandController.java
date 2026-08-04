package moe.dazecake.inquisition.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import moe.dazecake.inquisition.annotation.Login;
import moe.dazecake.inquisition.model.dto.skland.SklandCredentialDTO;
import moe.dazecake.inquisition.model.dto.skland.SklandStatusSyncDTO;
import moe.dazecake.inquisition.model.entity.SklandCredentialEntity;
import moe.dazecake.inquisition.model.vo.skland.SklandCredentialStatusVO;
import moe.dazecake.inquisition.service.impl.SklandCredentialService;
import moe.dazecake.inquisition.service.impl.SklandStatusSyncService;
import moe.dazecake.inquisition.utils.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Tag(name = "森空岛校准接口")
@RestController
public class SklandController {
    @Resource
    private SklandCredentialService credentialService;

    @Resource
    private SklandStatusSyncService statusSyncService;

    @Value("${inquisition.skland-sync-secret:}")
    private String syncSecret;

    @Operation(summary = "接收外部签到服务回传的森空岛状态")
    @PostMapping("/syncSklandStatus")
    public Result<String> syncStatus(
            @RequestHeader(value = "X-Inquisition-Skland-Token", required = false) String token,
            @RequestBody SklandStatusSyncDTO dto) {
        if (!validSyncToken(token)) {
            return Result.unauthorized("森空岛回传密钥无效");
        }
        return statusSyncService.sync(dto);
    }

    @Login
    @Operation(summary = "配置森空岛凭据")
    @PostMapping("/setSklandCredential")
    public Result<String> setCredential(@RequestBody SklandCredentialDTO dto) {
        if (dto == null || dto.getAccountId() == null) {
            return Result.paramError("账号ID不能为空");
        }
        credentialService.save(new SklandCredentialEntity()
                .setAccountId(dto.getAccountId())
                .setAccessToken(trim(dto.getAccessToken()))
                .setCred(trim(dto.getCred()))
                .setCredToken(trim(dto.getCredToken()))
                .setUid(trim(dto.getUid()))
                .setChannelMasterId(trim(dto.getChannelMasterId())));
        return Result.success("森空岛凭据已保存");
    }

    @Login
    @Operation(summary = "清除森空岛凭据")
    @PostMapping("/clearSklandCredential")
    public Result<String> clearCredential(Long accountId) {
        if (accountId == null) {
            return Result.paramError("账号ID不能为空");
        }
        credentialService.clear(accountId);
        return Result.success("森空岛凭据已清除");
    }

    @Login
    @Operation(summary = "查询森空岛凭据状态")
    @GetMapping("/getSklandCredentialStatus")
    public Result<SklandCredentialStatusVO> getStatus(Long accountId) {
        if (accountId == null) {
            return Result.paramError("账号ID不能为空");
        }
        var credential = credentialService.find(accountId).orElse(null);
        if (credential == null) {
            return Result.success(new SklandCredentialStatusVO(accountId, false, false,
                    null, null, null, null, null), "未配置");
        }
        var configured = nonBlank(credential.getCred()) && nonBlank(credential.getCredToken())
                && nonBlank(credential.getUid());
        var auto = nonBlank(credential.getAccessToken());
        return Result.success(new SklandCredentialStatusVO(accountId, configured, auto,
                credential.getUid(), credential.getChannelMasterId(), credential.getLastRefreshAt(),
                credential.getUpdatedAt(), credential.getLastError()), "查询成功");
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private boolean validSyncToken(String token) {
        if (!nonBlank(syncSecret) || !nonBlank(token)) {
            return false;
        }
        return MessageDigest.isEqual(syncSecret.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }
}
