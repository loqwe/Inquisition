package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.gson.Gson;
import moe.dazecake.inquisition.mapper.AdminMapper;
import moe.dazecake.inquisition.mapper.ProUserMapper;
import moe.dazecake.inquisition.model.dto.admin.AdminNoticeConfigDTO;
import moe.dazecake.inquisition.model.dto.admin.ChangeAdminPasswordDTO;
import moe.dazecake.inquisition.model.dto.admin.LoginAdminDTO;
import moe.dazecake.inquisition.model.entity.AdminEntity;
import moe.dazecake.inquisition.model.vo.admin.AddProUserBalanceDTO;
import moe.dazecake.inquisition.model.vo.admin.AdminLoginVO;
import moe.dazecake.inquisition.model.vo.admin.AdminNoticeConfigVO;
import moe.dazecake.inquisition.service.intf.AdminService;
import moe.dazecake.inquisition.utils.AdminNoticeConfigUtils;
import moe.dazecake.inquisition.utils.DynamicScheduleTask;
import moe.dazecake.inquisition.utils.Encoder;
import moe.dazecake.inquisition.utils.JWTUtils;
import moe.dazecake.inquisition.utils.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;

@Service
public class AdminServiceImpl implements AdminService {

    private static final String salt = "arklightscloud";
    private final Gson gson = new Gson();

    @Resource
    AdminMapper adminMapper;

    @Resource
    ProUserMapper proUserMapper;

    @Resource
    DynamicScheduleTask dynamicScheduleTask;

    @Value("${spring.mail.enable:false}")
    boolean enableMail;

    @Value("${spring.mail.to:}")
    String defaultAdminMail;

    @Override
    public Result<AdminLoginVO> loginAdmin(LoginAdminDTO loginAdminDTO) {
        if (loginAdminDTO.getUsername() == null || loginAdminDTO.getPassword() == null) {
            return Result.paramError("????????");
        }

        var admin = adminMapper.selectOne(
                Wrappers.<AdminEntity>lambdaQuery()
                        .eq(AdminEntity::getUsername, loginAdminDTO.getUsername())
                        .eq(AdminEntity::getPassword, Encoder.MD5(loginAdminDTO.getPassword() + salt))
        );

        if (admin != null) {
            return Result.success(new AdminLoginVO(JWTUtils.generateTokenForAdmin(admin)), "????");
        } else {
            return Result.unauthorized("????????");
        }
    }

    @Override
    public Result<String> updateAdminPassword(ChangeAdminPasswordDTO changeAdminPasswordDTO) {
        if (changeAdminPasswordDTO.getUsername() == null || changeAdminPasswordDTO.getOldPassword() == null || changeAdminPasswordDTO.getNewPassword() == null) {
            return Result.paramError("????????");
        }

        var admin = adminMapper.selectOne(
                Wrappers.<AdminEntity>lambdaQuery()
                        .eq(AdminEntity::getUsername, changeAdminPasswordDTO.getUsername())
                        .eq(AdminEntity::getPassword, Encoder.MD5(changeAdminPasswordDTO.getOldPassword() + salt))
        );

        if (admin != null) {
            admin.setPassword(Encoder.MD5(changeAdminPasswordDTO.getNewPassword() + salt));
            adminMapper.updateById(admin);
            return Result.success("????");
        } else {
            return Result.unauthorized("????????");
        }
    }

    @Override
    public Result<String> addBalanceForProUser(AddProUserBalanceDTO addProUserBalanceDTO) {
        var proUser = proUserMapper.selectById(addProUserBalanceDTO.getId());
        if (proUser != null) {
            proUser.setBalance(proUser.getBalance() + addProUserBalanceDTO.getBalance());
            proUserMapper.updateById(proUser);
            return Result.success("????");
        } else {
            return Result.notFound("?????");
        }
    }

    @Override
    public Result<AdminNoticeConfigVO> getAdminNoticeConfig(Long adminId) {
        var admin = adminMapper.selectById(adminId);
        if (admin == null) return Result.notFound("??????");
        var config = parseAdminNoticeConfig(admin);
        return Result.success(new AdminNoticeConfigVO(
                config.getMailEnable(),
                config.getAdminMail(),
                config.getSummarySchedule(),
                config.getWxPusherEnable(),
                config.getWxPusherUid(),
                config.getPushPlusEnable(),
                config.getPushPlusToken()
        ), "????");
    }

    @Override
    public Result<String> updateAdminNoticeConfig(Long adminId, AdminNoticeConfigDTO configDTO) {
        var admin = adminMapper.selectById(adminId);
        if (admin == null) return Result.notFound("??????");
        admin.setNotice(gson.toJson(normalizeAdminNoticeConfig(configDTO)));
        adminMapper.updateById(admin);
        return Result.success("????");
    }

    @Override
    public Result<String> sendAdminSummaryNow(Long adminId) {
        var admin = adminMapper.selectById(adminId);
        if (admin == null) return Result.notFound("管理员不存在");
        var targetAdmins = new ArrayList<AdminEntity>();
        targetAdmins.add(admin);
        dynamicScheduleTask.sendAdminSummaryNow(targetAdmins);
        return Result.success("实时汇总已发送");
    }

    private AdminNoticeConfigDTO parseAdminNoticeConfig(AdminEntity admin) {
        return AdminNoticeConfigUtils.parse(gson, admin.getNotice(), enableMail, defaultAdminMail);
    }

    private AdminNoticeConfigDTO normalizeAdminNoticeConfig(AdminNoticeConfigDTO configDTO) {
        return AdminNoticeConfigUtils.normalize(configDTO, enableMail, defaultAdminMail);
    }
}
