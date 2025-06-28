package org.example.automata.base;

import org.example.core.Clock;
import org.example.utils.Rational;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.stream.Collectors;

/**
 * 代表一个时钟重置集合。
 * 此类是不可变的。
 * @author Ayalyt
 */
public final class ResetSet {
    private static final Logger logger = LoggerFactory.getLogger(ResetSet.class);
    private final Map<Clock, Rational> resets;

    private final int hashCode;

    /**
     * 构造函数，通过 Map 创建 ResetSet。
     * @param resets 包含要重置的时钟及其新值的 Map。
     *               Map 中的值必须是有限的非负数。
     *               如果 Map 包含零时钟 (x0)，其值必须为 0，否则记录警告。
     */
    public ResetSet(Map<Clock, Rational> resets) {
        Map<Clock, Rational> tempMap = new HashMap<>();
        for (Map.Entry<Clock, Rational> entry : resets.entrySet()) {
            Clock clock = entry.getKey();
            Rational value = entry.getValue();

            if (clock.isZeroClock()) {
                logger.warn("零时钟 (x0) 被尝试重置。该操作被忽略。");
                continue;
            }

            if (!value.isFinite() || value.signum() < 0) {
                logger.warn("时钟 '" + clock.getName() + "' 的重置值 '" + value + "' 必须是有限非负数。此重置将被忽略。");
                continue;
            }
            tempMap.put(clock, value);
        }
        logger.info("创建 ResetSet: {}", tempMap);
        this.resets = Collections.unmodifiableMap(tempMap);
        this.hashCode = Objects.hash(tempMap);
    }

    /**
     * 构造函数，将指定时钟集合中的所有时钟重置为零。
     * @param clocksToReset 要重置为零的时钟集合。
     *                      如果集合包含零时钟 (x0)，将记录警告并忽略 x0。
     */
    public ResetSet(Set<Clock> clocksToReset) {
        Map<Clock, Rational> tempMap = new HashMap<>();
        for (Clock clock : clocksToReset) {
            if (clock.isZeroClock()) {
                logger.warn("零时钟 (x0) 被尝试重置。该操作被忽略。");
                continue;
            }
            tempMap.put(clock, Rational.ZERO);
        }
        logger.info("创建 ResetSet: {}", tempMap);
        this.resets = Collections.unmodifiableMap(tempMap);
        this.hashCode = Objects.hash(tempMap);
    }

    /**
     * 获取重置集合的不可修改视图。
     * @return Map<Clock, Rational>，包含要重置的时钟及其新值。
     */
    public Map<Clock, Rational> getResets() {
        logger.debug("获取 ResetSet: {}", resets);
        return resets;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ResetSet resetSet1 = (ResetSet) o;
        logger.debug("比较 ResetSet: this={}, other={}", resets, resetSet1.resets);
        return resets.equals(resetSet1.resets);
    }

    @Override
    public int hashCode() {
        logger.debug("请求了 {} 的哈希码: {}", resets, hashCode);
        return hashCode;
    }

    @Override
    public String toString() {
        if (resets.isEmpty()) {
            return "{}";
        }
        return "{" +
                resets.entrySet().stream()
                        .map(entry -> entry.getKey().getName() + "=" + entry.getValue())
                        .collect(Collectors.joining(", ")) +
                "}";
    }
}
