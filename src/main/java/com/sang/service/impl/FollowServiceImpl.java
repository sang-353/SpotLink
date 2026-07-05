package com.sang.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sang.dto.Result;
import com.sang.dto.UserDTO;
import com.sang.entity.Follow;
import com.sang.mapper.FollowMapper;
import com.sang.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sang.service.IUserService;
import com.sang.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        // 判断是关注还是取关
        if (isFollow) {
            // 关注，新增数据
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            boolean issuccess = save(follow);
            if (issuccess) {
                // 把关注用户id放入redis的set集合中 sadd follows:userId followUserId
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        }else {
            // 取关，删除数据
            boolean issuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            if (issuccess) {
                // 把关注用户id从redis的set集合中移除 srem follows:userId followUserId
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        // 判断当前用户是否关注了该用户
        boolean present = query().eq("user_id", UserHolder.getUser().getId())
                .eq("follow_user_id", followUserId).oneOpt()
                .isPresent();
        return Result.ok(present);
    }

    @Override
    public Result followCommons(Long id) {
        // 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 查询当前用户和目标用户的共同关注
        String key1 = "follows:" + userId;
        String key2 = "follows:" + id;
        // 求交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> users = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
