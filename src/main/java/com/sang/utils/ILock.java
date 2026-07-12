package com.sang.utils;

public interface ILock {

    /**
     * 尝试获取锁
     * @param timeoutSec 锁持有的超时时间，超时后自动释放
     * @return true代表获取锁成功，false代表获取锁失败
     */
    boolean tryLock(String name, long timeoutSec);

    /**
     * 释放锁
     */
    void unlock(String name);
}
