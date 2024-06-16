package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 不符合则返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //符合则生成验证码
        String code = RandomUtil.randomNumbers(6);
//        //保存到session
//        session.setAttribute("code",code);
        //将验证码保存到redis中去，并设置验证码的有效期
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY +phone,code,
                                                LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 发送短信验证码
        log.debug("发送短信验证码成功，验证码{}",code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            // 不符合则返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //校验验证码
        //从session中获取验证码
//        Object cacheCode = session.getAttribute("code");
        // 从redis中获取验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY +phone);
        String loginCode = loginForm.getCode();

        if(cacheCode==null||!cacheCode.equals(loginCode)){
            return Result.fail("验证码错误");
        }
        //判断用户是否存在  select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();
        if(user == null){
            //不存在，则创建新用户并保存
            user = createUserWithPhone(phone);
        }
        // 保存用户
        // session.setAttribute("user", user);  这种方式会泄露用户信息
        //        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        // 保存到redis中
        // 随机生成一个token作为登录令牌，并且作为key
        String token = UUID.randomUUID().toString();
        // 将User对象转为Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create().
                        setIgnoreNullValue(true).
                        setFieldValueEditor((filedName,filedValue) -> filedValue.toString()));
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token,userMap);
        stringRedisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL,TimeUnit.MINUTES);
        // 返回token

        return Result.ok(token);
    }

    private User createUserWithPhone(String phone){
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));

        // 保存用户到数据库
        save(user);
        return user;
    }
}
