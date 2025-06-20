package org.example.core;

import lombok.Getter;
import org.example.automata.base.ResetSet;
import org.example.utils.Rational;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 零时钟 (x0) 不在此映射中存储。
 * @author Ayalyt
 */
@Getter
public final class ClockValuation implements Comparable<ClockValuation>{

    private static final Logger logger = LoggerFactory.getLogger(ClockValuation.class);
    /**
     * @return Map<Clock, Rational>。
     */
    private final SortedMap<Clock, Rational> clockValuation;

    /**
     * 私有构造函数，通过 Map 创建 ClockValuation。
     * @param clockValuation 包含普通时钟及其值的 Map。
     */
    private ClockValuation(Map<Clock, Rational> clockValuation) {
        for (Map.Entry<Clock, Rational> entry : clockValuation.entrySet()) {
            Clock clock = entry.getKey();
            Rational value = entry.getValue();
            if (clock.isZeroClock()) {
                logger.warn("零时钟 (x0) 不在此映射中存储。");
                continue;
            }
            if (!value.isFinite() || value.compareTo(Rational.ZERO) < 0) {
                logger.error("初始化{}时遇到问题：时钟值必须是非负实数。", clockValuation);
                throw new IllegalArgumentException("时钟值必须是非负实数。");
            }
        }
        logger.debug("创建 ClockValuation: {}", clockValuation);
        this.clockValuation = Collections.unmodifiableSortedMap(new TreeMap<>(clockValuation));
    }

    /**
     * 工厂方法：创建一个新的 ClockValuation。
     * @param clockValuation 包含普通时钟及其值的 Map。
     * @return 新的 ClockValuation。
     */
    public static ClockValuation of(Map<Clock, Rational> clockValuation) {
        return new ClockValuation(clockValuation);
    }

    /**
     * 工厂方法：创建一个全零的 ClockValuation。
     * @param clocks 要创建全零的时钟集合。
     * @return 全零的 ClockValuation。
     */
    public static ClockValuation zero(Set<Clock> clocks) {
        Map<Clock, Rational> zeroValues = new HashMap<>();
        for (Clock clock : clocks) {
            if (clock.isZeroClock()) {
                continue;
            }
            zeroValues.put(clock, Rational.ZERO);
        }
        logger.debug("对于时钟列表{}创建零ClockValuation", clocks);
        return new ClockValuation(zeroValues);
    }

    /**
     * 延迟 ClockValuation。
     * @param delay
     * @return
     */
    public ClockValuation delay(Rational delay) {
        Map<Clock, Rational> delayedValues = new HashMap<>();
        for (Map.Entry<Clock, Rational> entry : clockValuation.entrySet()) {
            Clock clock = entry.getKey();
            Rational value = entry.getValue();
            delayedValues.put(clock, value.add(delay));
        }
        logger.debug("从{}延迟{}，到达{}",clockValuation, delay, delayedValues);
        return new ClockValuation(delayedValues);
    }

    /**
     * 重置指定时钟的值为零。
     * @param resetSet 要重置的时钟集合。
     * @return 重置后的 ClockValuation。
     **/
    public ClockValuation reset(ResetSet resetSet) {
        Map<Clock, Rational> resetValues = new HashMap<>(clockValuation);
        logger.debug("对{}应用重置{}", clockValuation, resetSet);
        for (Map.Entry<Clock, Rational> entry : resetSet.getResets().entrySet()) {
            Clock clock = entry.getKey();
            Rational value = entry.getValue();
            // 如果时钟在重置集合中，则将其值设置为零
            if (resetSet.getResets().containsKey(clock)) {
                resetValues.put(clock, value);
            } else {
                logger.warn("时钟 '" + clock.getName() + "' 不在重置集合中。");
            }
        }
        logger.debug("重置后的ClockValuation: {}", resetValues);
        return new ClockValuation(resetValues);
    }


    /**
     * 获取指定时钟的值。
     * 如果是零时钟 (x0)，则直接返回 Rational.ZERO。
     * @param clock 要获取值的时钟。
     * @return 时钟的 Rational 值。
     */
    public Rational getValue(Clock clock) {
        if (clock.isZeroClock()) {
            return Rational.ZERO;
        }
        if(!clockValuation.containsKey(clock)) {
            logger.warn("从{}获取{}的时钟值时出错: 不存在这样的时钟。返回默认值0，请注意。", clockValuation, clock);
            return Rational.ZERO;
        }
        return clockValuation.get(clock);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ClockValuation that = (ClockValuation) o;
        return clockValuation.equals(that.clockValuation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clockValuation);
    }

    @Override
    public String toString() {
        return "{" +
                clockValuation.entrySet().stream()
                        .map(entry -> entry.getKey().getName() + "=" + entry.getValue())
                        .collect(Collectors.joining(", ")) +
                "}";
    }

    @Override
    public int compareTo(ClockValuation other) {
        // 获取所有涉及到的时钟，并排序 (确保比较所有相关时钟，即使某个 valuation 中没有显式赋值)
        Set<Clock> allClocks = new TreeSet<>(Comparator.comparingInt(Clock::getId));
        allClocks.addAll(this.clockValuation.keySet());
        allClocks.addAll(other.clockValuation.keySet());
        logger.debug("比较{}和{}", this, other);

        for (Clock clock : allClocks) {
            Rational thisValue = this.getValue(clock);
            Rational otherValue = other.getValue(clock);

            int comparison = thisValue.compareTo(otherValue);
            if (comparison != 0) {
                logger.debug("由于{}和{}中{}对于值的比较，结果为{}", this, other, clock, comparison);
                return comparison;
            }
        }
        return 0;
    }
}
