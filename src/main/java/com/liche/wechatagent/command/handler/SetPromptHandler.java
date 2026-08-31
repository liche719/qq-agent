package com.liche.wechatagent.command.handler;

import com.liche.wechatagent.command.CommandHandler;
import com.liche.wechatagent.exception.BizException;
import com.liche.wechatagent.user.UserService;
import org.springframework.stereotype.Component;

/** /set-prompt 内容：更新当前用户的专属人设提示词 */
@Component
public class SetPromptHandler implements CommandHandler {

    private final UserService userService;

    public SetPromptHandler(UserService userService) {
        this.userService = userService;
    }

    @Override
    public String name() {
        return "set-prompt";
    }

    @Override
    public String description() {
        return "更新你的专属人设，例如：/set-prompt 你是我的健身教练";
    }

    @Override
    public String handle(String args, String userId) {
        if (args.isBlank()) {
            return "请提供新的身份设定，例如：/set-prompt 你是我的健身教练，说话直接一点。";
        }
        try {
            userService.updatePersona(userId, args);
            String preview = args.length() > 50 ? args.substring(0, 50) + "…" : args;
            return "好，人设已更新为：「" + preview + "」。从现在起我就按这个人设陪你。";
        } catch (BizException e) {
            return e.getMessage();
        }
    }
}
