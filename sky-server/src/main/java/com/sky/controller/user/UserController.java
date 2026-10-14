package com.sky.controller.user;

import com.sky.dto.UserLoginDTO;
import com.sky.result.Result;
import com.sky.service.UserService;
import com.sky.vo.UserLoginVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("userUserController")
@RequestMapping("/user/user")
@Tag(name = "用户登录接口")
@Slf4j
public class UserController {
    @Autowired
    private UserService userService;

    @PostMapping("/login")
    @Operation(summary = "微信小程序登录")
    public Result<UserLoginVO> login(@RequestBody UserLoginDTO dto) {
        log.info("收到微信小程序登录请求");
        UserLoginVO vo = userService.login(dto);
        log.info("微信小程序登录成功：用户id={}", vo.getId());
        return Result.success(vo);
    }
}
