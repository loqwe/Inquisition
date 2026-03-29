package moe.dazecake.inquisition.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.gson.Gson;
import com.zjiecode.wxpusher.client.bean.Message;
import lombok.extern.slf4j.Slf4j;
import moe.dazecake.inquisition.mapper.AccountMapper;
import moe.dazecake.inquisition.mapper.AdminMapper;
import moe.dazecake.inquisition.model.dto.admin.AdminNoticeConfigDTO;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.AdminEntity;
import moe.dazecake.inquisition.service.intf.MessageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Slf4j
@Service
public class MessageServiceImpl implements MessageService {
    private final Gson gson = new Gson();

    @Value("${spring.mail.enable:false}")
    boolean enableMail;

    @Value("${wx-pusher.enable:false}")
    boolean enableWxPusher;

    @Value("${spring.mail.to:}")
    String adminMail;

    @Resource
    EmailServiceImpl emailService;

    @Resource
    WXPusherServiceImpl wxPusherService;

    @Resource
    AccountMapper accountMapper;

    @Resource
    AdminMapper adminMapper;

    @Resource
    PushPlusServiceImpl pushPlusService;

    @Override
    public void push(AccountEntity account, String title, String content) {
        //微信推送
        if (enableWxPusher && account.getNotice().getWxUID().getEnable()) {
            wxPusherService.push(Message.CONTENT_TYPE_MD,
                    "# " + title + "\n\n" +
                            content,
                    account.getNotice().getWxUID().getText(),
                    null);
        }

        //邮件推送
        if (enableMail && account.getNotice().getMail().getEnable()) {
            try {
                emailService.sendSimpleMail(account.getNotice().getMail().getText(), title,
                        content);
            } catch (Exception e) {
                //正则匹配是否为邮箱地址格式
                if (!account.getNotice().getMail().getText().matches("^[a-zA-Z0-9_-]+@[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+$")) {
                    log.info("【审判庭】 邮件推送失败 " + account.getAccount() + ": " + account.getNotice().getMail().getText() + " 不是一个有效的邮箱地址");
                    account.getNotice().getMail().setEnable(false);
                    accountMapper.updateById(account);
                    return;
                }
                e.printStackTrace();
                log.warn("【审判庭】 邮件推送失败 " + account.getAccount() + ": " + account.getNotice().getMail().getText());
            }
        }
    }

    @Override
    public void pushAdmin(String title, String content) {
        if (enableMail && adminMail != null && !adminMail.isBlank()) {
            emailService.sendSimpleMail(adminMail, title, content);
        }
        var markdown = "# " + title + "\n\n" + content.replace("\n", "\n\n");
        adminMapper.selectList(Wrappers.<AdminEntity>lambdaQuery().eq(AdminEntity::getDelete, 0)).forEach(admin -> {
            var config = parseAdminNoticeConfig(admin.getNotice());
            if (enableWxPusher && config.getWxPusherEnable() && !config.getWxPusherUid().isBlank()) {
                wxPusherService.push(Message.CONTENT_TYPE_MD, markdown, config.getWxPusherUid(), null);
            }
            if (config.getPushPlusEnable() && !config.getPushPlusToken().isBlank()) {
                pushPlusService.push(config.getPushPlusToken(), title, markdown);
            }
        });
    }

    private AdminNoticeConfigDTO parseAdminNoticeConfig(String notice) {
        try {
            if (notice == null || notice.isBlank()) return new AdminNoticeConfigDTO();
            var config = gson.fromJson(notice, AdminNoticeConfigDTO.class);
            if (config == null) return new AdminNoticeConfigDTO();
            config.setWxPusherEnable(Boolean.TRUE.equals(config.getWxPusherEnable()));
            config.setWxPusherUid(config.getWxPusherUid() == null ? "" : config.getWxPusherUid().trim());
            config.setPushPlusEnable(Boolean.TRUE.equals(config.getPushPlusEnable()));
            config.setPushPlusToken(config.getPushPlusToken() == null ? "" : config.getPushPlusToken().trim());
            return config;
        } catch (Exception e) {
            return new AdminNoticeConfigDTO();
        }
    }
}
