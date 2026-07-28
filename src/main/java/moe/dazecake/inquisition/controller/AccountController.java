package moe.dazecake.inquisition.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import moe.dazecake.inquisition.annotation.Login;
import moe.dazecake.inquisition.model.dto.account.AccountDTO;
import moe.dazecake.inquisition.model.dto.account.AccountDispatchConfigDTO;
import moe.dazecake.inquisition.model.dto.account.AccountIDDTO;
import moe.dazecake.inquisition.model.dto.account.AddAccountDTO;
import moe.dazecake.inquisition.model.dto.user.UserStatusSTO;
import moe.dazecake.inquisition.model.vo.account.AccountWithSanVO;
import moe.dazecake.inquisition.model.vo.query.PageQueryVO;
import moe.dazecake.inquisition.service.impl.AccountServiceImpl;
import moe.dazecake.inquisition.service.impl.UserServiceImpl;
import moe.dazecake.inquisition.utils.Result;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Set;

@Tag(name = "账号接口")
@ResponseBody
@RestController
public class AccountController {

    @Resource
    AccountServiceImpl accountService;

    @Resource
    ObjectMapper objectMapper;

    @Resource
    UserServiceImpl userService;

    @Login
    @Operation(summary = "增加账号")
    @PostMapping("/addAccount")
    public Result<String> addAccount(@RequestBody AddAccountDTO addAccountDTO) {
        accountService.addAccount(addAccountDTO);
        return Result.success(null, "添加成功");
    }


    @Login
    @Operation(summary = "从速通迁移账号")
    @PostMapping("/transferAccountFromArkLights")
    public Result<String> transferAccountFromArkLights(@RequestBody HashMap<String, String> accountJson) {
        return Result.success(null, "已成功添加" + accountService.transferAccount(accountJson) + "个账号");
    }


    @Login
    @Operation(summary = "删除账号")
    @PostMapping("/delAccount")
    public Result<String> delAccount(@RequestBody AccountIDDTO accountIDDTO) {
        accountService.deleteAccount(accountIDDTO.getId());
        return Result.success(null, "删除成功");
    }

    @Login
    @Operation(summary = "分页查询账号")
    @GetMapping("/showAccount")
    public Result<PageQueryVO<AccountWithSanVO>> showAccount(Long current, Long size, String taskType, String freeze, String expired, String deleted) {
        return Result.success(accountService.queryAllAccount(current, size, taskType, freeze, expired, deleted), "查询成功");
    }

    @Login
    @Operation(summary = "搜索账号")
    @GetMapping("/searchAccount")
    public Result<PageQueryVO<AccountWithSanVO>> searchAccount(Long current, Long size, String keyword) {
        return Result.success(accountService.queryAccount(current, size, keyword), "查询成功");
    }

    @Login
    @Operation(summary = "更新账号")
    @PostMapping("/updateAccount")
    public Result<String> updateAccount(@RequestBody JsonNode accountJson) {
        if (accountJson == null || !accountJson.isObject() || !accountJson.hasNonNull("id")) {
            return Result.paramError("id不允许为空");
        }
        var presentFields = new HashSet<String>();
        accountJson.fieldNames().forEachRemaining(presentFields::add);
        try {
            AccountDispatchConfigDTO dispatchConfig = null;
            if (presentFields.contains("dispatchConfig")) {
                var dispatchNode = accountJson.get("dispatchConfig");
                if (dispatchNode == null || !dispatchNode.isObject()) {
                    return Result.paramError("dispatchConfig格式错误");
                }
                var allowedFields = Set.of("dispatchMode", "scheduleTime");
                var fieldNames = dispatchNode.fieldNames();
                while (fieldNames.hasNext()) {
                    if (!allowedFields.contains(fieldNames.next())) {
                        return Result.paramError("dispatchConfig包含未知字段");
                    }
                }
                dispatchConfig = objectMapper.convertValue(
                        dispatchNode, AccountDispatchConfigDTO.class);
            }
            var accountFields = ((ObjectNode) accountJson.deepCopy());
            accountFields.remove("dispatchConfig");
            accountService.updateAccount(objectMapper.convertValue(accountFields, AccountDTO.class),
                    presentFields, dispatchConfig);
            return Result.success(null, "更新成功");
        } catch (IllegalArgumentException exception) {
            return Result.paramError(exception.getMessage());
        }
    }

    @Login
    @Operation(summary = "重置刷新次数")
    @PostMapping("/resetRefresh")
    public Result<String> resetRefresh(@RequestBody AccountIDDTO accountIDDTO) {
        accountService.resetAccountRefresh(accountIDDTO.getId(), 1);
        return Result.success(null, "重置成功");
    }

    @Login
    @Operation(summary = "账号立即作战")
    @PostMapping("/startAccountByAdmin")
    public Result<String> startAccountByAdmin(@RequestBody AccountIDDTO accountIDDTO) {
        return Result.success(null, accountService.forceFightAccount(accountIDDTO.getId(), true));
    }

    @Login
    @Operation(summary = "重置账号动态信息")
    @PostMapping("/resetAccountDynamicInfo")
    public Result<String> fixAccount(@RequestBody AccountIDDTO accountIDDTO) {
        return Result.success(null, accountService.resetAccountDynamicInfo(accountIDDTO.getId()));
    }

    @Login
    @Operation(summary = "查询用户状态")
    @GetMapping("/showUserStatus")
    public Result<UserStatusSTO> showUserStatus(Long userId) {
        return userService.showMyStatus(userId);
    }

    @Login
    @Operation(summary = "查询用户当前理智")
    @GetMapping("/showUserSan")
    public Result<String> showUserSan(Long userId) {
        return userService.showMySan(userId);
    }
}
