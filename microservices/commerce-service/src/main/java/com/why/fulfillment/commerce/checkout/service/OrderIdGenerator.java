package com.why.fulfillment.commerce.checkout.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单号生成。
 *
 * <p><b>这个类是脚手架，不是你的作品。</b>如果面试被问到发号器，不要拿这段实现去答——
 * 它刻意做成了最简单的能用版本，和真正的 Snowflake 有实质差别（见下）。
 * 要讲发号器就自己重写一遍，重写之前先想清楚这里每一条取舍为什么是这样。</p>
 *
 * <p>位分配：{@code [毫秒差值 41 位][节点号 8 位][序号 13 位]}，共 62 位，
 * 最高位留 0 保证是正数。</p>
 *
 * <p><b>为什么订单号要按时间递增：</b>它是 InnoDB 聚簇索引的主键。随机主键会让
 * 每次插入都落到不同的页上，造成页分裂和随机写；递增主键只在最后一页追加。
 * UUID 做订单主键之所以是个坏选择，主要就是这个原因，而不是长度。</p>
 *
 * <p><b>和 Snowflake 的差别，别混为一谈：</b></p>
 * <ul>
 *   <li>序号是一个一直往前走的计数器，不是「每毫秒归零」。这样实现简单，
 *       代价是同一毫秒内的号不连续。反正没人依赖连续性。</li>
 *   <li>没有处理时钟回拨。真 Snowflake 会在检测到回拨时等待或报错；这里不会，
 *       靠的是「序号不归零」——回拨后要撞号，得等序号绕完 8192 个才可能，
 *       而绕完 8192 个的同时时钟还停在被回拨的那一毫秒，实际上不会发生。
 *       这是「碰巧安全」，不是「设计上安全」，两者在面试里是不同的答案。</li>
 *   <li>节点号来自配置而不是注册中心。多实例部署时必须手工保证不重复，
 *       配重了就会发出重复订单号，而且要等到唯一键报错才会被发现。</li>
 * </ul>
 */
@Component
public class OrderIdGenerator {

    /** 2026-01-01T00:00:00Z。起点越晚，41 位能表示的年份越靠后。 */
    private static final long EPOCH_MILLIS = 1767225600000L;

    private static final int NODE_BITS = 8;
    private static final int SEQUENCE_BITS = 13;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;
    private static final long MAX_NODE_ID = (1L << NODE_BITS) - 1;

    private final AtomicLong sequence = new AtomicLong();
    private final long nodeId;

    public OrderIdGenerator(@Value("${commerce.node-id:0}") long nodeId) {
        if (nodeId < 0 || nodeId > MAX_NODE_ID) {
            // 启动就失败，而不是把越界的节点号截断后带病运行——
            // 截断的结果是两个实例发出同一段号，几天后才在订单重复上暴露出来。
            throw new IllegalArgumentException("commerce.node-id must be between 0 and " + MAX_NODE_ID);
        }
        this.nodeId = nodeId;
    }

    public long next() {
        long elapsed = System.currentTimeMillis() - EPOCH_MILLIS;
        if (elapsed < 0) {
            throw new IllegalStateException("system clock is before the id epoch");
        }
        long seq = sequence.getAndIncrement() & SEQUENCE_MASK;
        return (elapsed << (NODE_BITS + SEQUENCE_BITS)) | (nodeId << SEQUENCE_BITS) | seq;
    }
}
