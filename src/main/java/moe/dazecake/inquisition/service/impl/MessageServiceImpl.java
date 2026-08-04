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
import moe.dazecake.inquisition.utils.AdminNoticeConfigUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.LinkedHashSet;
import java.util.List;

@Slf4j
@Service
public class MessageServiceImpl implements MessageService {
    private final Gson gson = new Gson();

    @Value("${spring.mail.enable:false}")
    boolean enableMail;

    @Value("${wx-pusher.enable:false}")
    boolean enableWxPusher;

    @Value("${spring.mail.to:}")
    String defaultAdminMail;

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
        if (enableWxPusher && account.getNotice().getWxUID().getEnable()) {
            try {
                wxPusherService.push(Message.CONTENT_TYPE_MD,
                        "# " + title + "\n\n" + content,
                        account.getNotice().getWxUID().getText(),
                        null);
            } catch (Exception exception) {
                log.warn("WXPusher user notification failed for account {}", account.getId(), exception);
            }
        }

        if (enableMail && account.getNotice().getMail().getEnable()) {
            try {
                emailService.sendSimpleMail(account.getNotice().getMail().getText(), title, content);
            } catch (Exception e) {
                if (!account.getNotice().getMail().getText().matches("^[a-zA-Z0-9_-]+@[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+$")) {
                    log.info("????? ??????{}: {} ???????????", account.getAccount(), account.getNotice().getMail().getText());
                    account.getNotice().getMail().setEnable(false);
                    accountMapper.updateById(account);
                    return;
                }
                log.warn("????? ??????{}: {}", account.getAccount(), account.getNotice().getMail().getText(), e);
            }
        }
    }

    @Override
    public void pushAdmin(String title, String content) {
        pushAdmin(adminMapper.selectList(Wrappers.<AdminEntity>lambdaQuery()
                .and(wrapper -> wrapper.eq(AdminEntity::getDelete, 0)
                        .or()
                        .isNull(AdminEntity::getDelete))), title, content);
    }

    public void pushAdmin(List<AdminEntity> admins, String title, String content) {
        var markdown = "# " + title + "\n\n" + content.replace("\n", "\n\n");
        var emailTargets = new LinkedHashSet<String>();
        var wxTargets = new LinkedHashSet<String>();
        var pushPlusTargets = new LinkedHashSet<String>();
        admins.forEach(admin -> {
            var config = parseAdminNoticeConfig(admin.getNotice());
            if (enableMail && config.getMailEnable() && !config.getAdminMail().isBlank() && emailTargets.add(config.getAdminMail())) {
                try {
                    emailService.sendSimpleMail(config.getAdminMail(), title, content);
                } catch (Exception e) {
                    log.warn("????? ?????????: {}", config.getAdminMail(), e);
                }
            }
            if (enableWxPusher && config.getWxPusherEnable() && !config.getWxPusherUid().isBlank() && wxTargets.add(config.getWxPusherUid())) {
                try {
                    wxPusherService.push(Message.CONTENT_TYPE_MD, markdown, config.getWxPusherUid(), null);
                } catch (Exception exception) {
                    log.warn("WXPusher admin notification failed for uid {}", config.getWxPusherUid(), exception);
                }
            }
            if (config.getPushPlusEnable() && !config.getPushPlusToken().isBlank() && pushPlusTargets.add(config.getPushPlusToken())) {
                pushPlusService.push(config.getPushPlusToken(), title, markdown);
            }
        });
    }

    private AdminNoticeConfigDTO parseAdminNoticeConfig(String notice) {
        return AdminNoticeConfigUtils.parse(gson, notice, enableMail, defaultAdminMail);
    }
}
