package moe.dazecake.inquisition.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import moe.dazecake.inquisition.annotation.Login;
import moe.dazecake.inquisition.model.dto.log.AddImageDTO;
import moe.dazecake.inquisition.model.dto.log.AddLogDTO;
import moe.dazecake.inquisition.model.dto.log.LogDTO;
import moe.dazecake.inquisition.model.dto.log.LogIDDTO;
import moe.dazecake.inquisition.model.vo.query.PageQueryVO;
import moe.dazecake.inquisition.service.impl.LogServiceImpl;
import moe.dazecake.inquisition.utils.Result;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@Tag(name = "日志接口")
@ResponseBody
@RestController
public class LogController {

    @Resource
    LogServiceImpl logService;

    @Operation(summary = "增加日志")
    @PostMapping("/addLog")
    public Result<String> addLog(@RequestParam(required = false) String deviceToken,
                                 @RequestBody AddLogDTO addLogDTO) {
        if ((addLogDTO.getFrom() == null || addLogDTO.getFrom().isBlank())
                && deviceToken != null && !deviceToken.isBlank()) {
            addLogDTO.setFrom(deviceToken);
        }
        logService.addLog(addLogDTO, false);
        return Result.success("添加成功");
    }

    @Operation(summary = "上传图片")
    @PostMapping("/uploadImage")
    public Result<String> uploadImage(@RequestBody AddImageDTO addImageDTO) {
        return logService.uploadImage(addImageDTO);
    }

    @Login
    @Operation(summary = "删除日志")
    @PostMapping("/delLog")
    public Result<String> delLog(@RequestBody LogIDDTO logIDDTO) {
        logService.deleteLog(logIDDTO.getId());
        return Result.success("删除成功");
    }

    @Login
    @Operation(summary = "查询日志")
    @GetMapping("/showLog")
    public Result<PageQueryVO<LogDTO>> showLog(Long current, Long size) {
        return Result.success(logService.queryAllLog(current, size), "查询成功");
    }

    @Login
    @Operation(summary = "按名称或账号搜索日志")
    @GetMapping({"/searchLog", "/searchLogByAccount"})
    public Result<PageQueryVO<LogDTO>> searchLog(String keyword, String account, Long current, Long size) {
        var searchKeyword = (keyword != null && !keyword.isBlank()) ? keyword : account;
        if (searchKeyword == null || searchKeyword.isBlank()) {
            return Result.success(logService.queryAllLog(current, size), "查询成功");
        }
        return Result.success(logService.queryLogByKeyword(searchKeyword.trim(), current, size), "查询成功");
    }
}
