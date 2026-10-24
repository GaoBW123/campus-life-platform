package com.campus.life;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.campus.life.dto.UserDTO;
import com.campus.life.entity.User;
import com.campus.life.service.IUserService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class UserTokenTest {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void generateTokens() throws IOException {
        // 1. 从数据库中查询出前 1000 个用户（如果你的表里只有几百个，就查全部）
        // 注意：压测前请确保你的 tb_user 表里有足够的用户数据
        List<User> userList = userService.lambdaQuery().last("limit 1000").list();

        // 2. 准备写文件的流 (默认生成在项目的根目录下)
        FileWriter fileWriter = new FileWriter("tokens.txt");
        BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);

        for (User user : userList) {
            // 3. 生成随机的 UUID 作为 Token
            String token = UUID.randomUUID().toString(true);

            // 4. 将 User 对象转为 UserDTO (脱敏)
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

            // 5. 将 UserDTO 转为 Map，注意：StringRedisTemplate 要求 Map 的 Key 和 Value 都必须是 String
            Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                    CopyOptions.create()
                            .setIgnoreNullValue(true)
                            .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

            // 6. 将伪造的登录信息存入 Redis
            String tokenKey = "login:token:" + token;
            stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
            // 设置 30 分钟过期时间（足够你跑完压测了）
            stringRedisTemplate.expire(tokenKey, 30, TimeUnit.MINUTES);

            // 7. 将 Token 写入 txt 文件，每行一个
            bufferedWriter.write(token);
            bufferedWriter.newLine();
        }

        // 8. 关闭流
        bufferedWriter.close();
        fileWriter.close();

        System.out.println("✅ 1000个压测 Token 已经成功生成并写入 Redis，文件路径为项目根目录下的 tokens.txt");
    }
}