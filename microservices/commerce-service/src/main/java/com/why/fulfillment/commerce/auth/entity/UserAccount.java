package com.why.fulfillment.commerce.auth.entity;

import java.time.LocalDateTime;

/** user_account 的持久化模型。{@code passwordHash} 永远不出现在任何返回体或日志里。 */
public class UserAccount {

    private Long id;
    private String username;
    private String passwordHash;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    /**
     * 覆盖 toString，防止有人 log.info("user={}", account) 时把摘要打进日志。
     * 摘要不是明文，但它是离线爆破的输入，不该出现在日志文件里。
     */
    @Override
    public String toString() {
        return "UserAccount{id=" + id + ", username='" + username + "', status=" + status + "}";
    }
}
