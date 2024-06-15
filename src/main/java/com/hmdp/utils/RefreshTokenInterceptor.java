package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class RefreshTokenInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 获取session，找到用户信息，并进行判断。
//        HttpSession session = request.getSession();
//        Object user = session.getAttribute("user");
        // 获取请求头中的token。然后获取redis'中的用户
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)){
            return true;
        }
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY + token);
        if(userMap.isEmpty()){
            return true;
        }
        // 将查询到的数据转换为UserDTO,并保存到ThreadLocal
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        UserDTOHolder.saveUser(userDTO);
        // 刷新用户的有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token,LOGIN_USER_TTL, TimeUnit.MINUTES);
        /*
        为了保证数据的安全性，不能直接在session中保存保存User类型，需要借助UserDTO来进行保存
         */
        //UserHolder.saveUser((User) user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
//        UserHolder.removeUser();
        UserDTOHolder.removeUser();
    }
}
