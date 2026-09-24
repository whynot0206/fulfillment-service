package com.why.fulfillment.commerce.auth.mapper;

import com.why.fulfillment.commerce.auth.entity.UserAccount;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserAccountMapper {

    /**
     * 注册。
     *
     * <p>不做「先查再插」——并发注册同名用户时两个请求都会查到「不存在」。
     * 唯一键 uk_user_account_username 是判重的唯一依据，冲突由
     * DuplicateKeyException 体现。</p>
     */
    @Insert("""
            insert into user_account (username, password_hash, status)
            values (#{username}, #{passwordHash}, 1)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserAccount account);

    @Select("""
            select id, username, password_hash, status, create_time, update_time
            from user_account where username = #{username}
            """)
    UserAccount selectByUsername(@Param("username") String username);

    @Select("""
            select id, username, password_hash, status, create_time, update_time
            from user_account where id = #{userId}
            """)
    UserAccount selectById(@Param("userId") Long userId);
}
