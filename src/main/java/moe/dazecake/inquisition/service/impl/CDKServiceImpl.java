package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import moe.dazecake.inquisition.constant.enums.CDKWrapper;
import moe.dazecake.inquisition.constant.enums.TaskType;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.mapper.CDKMapper;
import moe.dazecake.inquisition.mapper.mapstruct.CDKConvert;
import moe.dazecake.inquisition.model.dto.cdk.CDKDTO;
import moe.dazecake.inquisition.model.dto.cdk.CreateCDKDTO;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.CDKEntity;
import moe.dazecake.inquisition.model.vo.cdk.CDKListVO;
import moe.dazecake.inquisition.service.intf.CDKService;
import moe.dazecake.inquisition.utils.DynamicInfo;
import moe.dazecake.inquisition.utils.Result;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class CDKServiceImpl implements CDKService {

    @Resource
    AccountMapper accountMapper;

    @Resource
    CDKMapper cdkMapper;

    @Resource
    LogServiceImpl logService;

    @Resource
    DynamicInfo dynamicInfo;

    @Resource
    AccountServiceImpl accountService;

    @Override
    public Result<String> activateCDK(Long id, String cdk) {

        var accountEntity = accountMapper.selectById(id);
        var cdkEntity = cdkMapper.selectOne(
                Wrappers.<CDKEntity>lambdaQuery()
                        .eq(CDKEntity::getCdk, cdk)
                        .eq(CDKEntity::getUsed, 0)
        );

        if (accountEntity == null) {
            return Result.notFound("账号不存在");
        }

        if (cdkEntity == null) {
            return Result.notFound("激活码不存在或已使用");
        }

        var taskType = TaskType.getByStr(cdkEntity.getType());
        String msg = "激活成功";
        switch (taskType) {
            case DAILY:
                int days = 30;
                try {
                    days = Integer.parseInt(cdkEntity.getParam().split("\\|")[0].trim());
                } catch (Exception ignored) {}
                accountService.addAccountExpireTime(id, 24 * days);
                msg = "成功充入 " + days + " 天授权时长";
                break;
            case REFRESH:
                int runs = 1;
                try {
                    runs = Integer.parseInt(cdkEntity.getParam().split("\\|")[0].trim());
                } catch (Exception ignored) {}
                int currentRefresh = accountEntity.getRefresh() == null ? 0 : accountEntity.getRefresh();
                accountEntity.setRefresh(currentRefresh + runs);
                accountEntity.setUpdateTime(LocalDateTime.now());
                accountMapper.updateById(accountEntity);
                msg = "成功充入 " + runs + " 次立刻作战次数";
                break;
            case ROGUE:
            case ROGUE2:
            case SAND_FIRE:
                accountService.initiateTaskConversion(taskType, id, cdkEntity.getParam());
                break;
            default:
                break;
        }

        cdkEntity.setUsed(1);
        if (cdkEntity.getIsAgent() == 1) {
            accountEntity.setAgent(cdkEntity.getAgent());
            accountMapper.updateById(accountEntity);
        }

        cdkMapper.updateById(cdkEntity);

        dynamicInfo.setUserSan(accountEntity.getId(), 135, 135);

        return Result.success(msg);
    }

    @Override
    public Result<String> createUserByCDK(AccountEntity accountEntity, String cdk) {

        var cdkEntity = cdkMapper.selectOne(
                Wrappers.<CDKEntity>lambdaQuery()
                        .eq(CDKEntity::getCdk, cdk)
                        .eq(CDKEntity::getUsed, 0)
        );

        if (cdkEntity == null) {
            return Result.notFound("激活码不存在或已使用");
        }

        if ("refresh".equalsIgnoreCase(cdkEntity.getType())) {
            return Result.paramError("创建账号需使用授权天数卡密，作战次数卡密请登录后在个人中心兑换");
        }

        if ("daily".equalsIgnoreCase(cdkEntity.getType())) {
            long days = 30;
            try {
                days = Long.parseLong(cdkEntity.getParam().split("\\|")[0].trim());
            } catch (Exception ignored) {}
            accountEntity.setExpireTime(LocalDateTime.now().plusDays(days));
        } else {
            accountEntity.setExpireTime(LocalDateTime.now());
        }

        cdkEntity.setUsed(1);
        if (cdkEntity.getIsAgent() == 1) {
            accountEntity.setAgent(cdkEntity.getAgent());
        } else {
            accountEntity.setAgent(0L);
        }

        accountEntity.setCreateTime(LocalDateTime.now());
        accountEntity.setUpdateTime(LocalDateTime.now());

        cdkMapper.updateById(cdkEntity);
        logService.logInfo("CDK使用", "使用CDK " + cdk + " 创建了账号 " + accountEntity.getAccount());
        accountMapper.insert(accountEntity);

        if (!"daily".equals(cdkEntity.getType())) {
            var taskType = TaskType.getByStr(cdkEntity.getType());
            accountService.initiateTaskConversion(taskType, accountEntity.getId(), cdkEntity.getParam());
        }


        var account = accountMapper.selectOne(Wrappers.<AccountEntity>lambdaQuery()
                .eq(AccountEntity::getId, accountEntity.getId())
                .eq(AccountEntity::getDelete, 0));
        accountService.forceFightAccount(account.getId(), true);

        return Result.success("创建成功");
    }

    @Override
    public Result<String> createCDK(CreateCDKDTO createCDKDTO) {
        ArrayList<CDKEntity> newCDKList = new ArrayList<>();
        for (int i = 0; i < createCDKDTO.getCount(); i++) {
            var cdkEntity = new CDKEntity();
            cdkEntity.setId(0L);
            cdkEntity.setCdk(RandomStringUtils.randomAlphabetic(32));
            cdkEntity.setType(createCDKDTO.getType());
            cdkEntity.setParam(createCDKDTO.getParam());
            cdkEntity.setTag(createCDKDTO.getTag());
            cdkEntity.setIsAgent(createCDKDTO.getIsAgent() ? 1 : 0);
            cdkEntity.setAgent(createCDKDTO.getAgent());
            cdkEntity.setUsed(0);
            newCDKList.add(cdkEntity);
        }
        newCDKList.forEach(cdkMapper::insert);
        return Result.success("创建成功");
    }

    @Override
    public LambdaQueryWrapper<CDKEntity> createCDKWrapper(CDKWrapper cdkWrapper, String keyword) {
        switch (cdkWrapper) {
            case TYPE:
                return Wrappers.<CDKEntity>lambdaQuery()
                        .eq(CDKEntity::getType, keyword);
            case TAG:
                return Wrappers.<CDKEntity>lambdaQuery()
                        .eq(CDKEntity::getTag, keyword);
            case AGENT:
                return Wrappers.<CDKEntity>lambdaQuery()
                        .eq(CDKEntity::getAgent, keyword)
                        .eq(CDKEntity::getUsed, 0);
        }
        return null;
    }

    @Override
    public Result<CDKListVO> queryCDKList(CDKWrapper cdkWrapper, String keyword) {
        var cdkList = cdkMapper.selectList(createCDKWrapper(cdkWrapper, keyword));
        List<CDKDTO> list = new ArrayList<>();
        cdkList.forEach(cdk -> list.add(CDKConvert.INSTANCE.toCDKDTO(cdk)));
        var cdkListVO = new CDKListVO();
        cdkListVO.setCdkList(list);

        return Result.success(cdkListVO, "查询成功");
    }
}
