package org.example.core; // 放在 core 包下

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Ayalyt
 */
@Getter
public final class Clock implements Comparable<Clock> {

    private static final Logger logger = LoggerFactory.getLogger(Clock.class);

    // AtomicInteger 保证唯一性和线程安全
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1); // 从1开始，0留给零时钟

    // 零时钟的单例实例
    public static final Clock ZERO_CLOCK = new Clock(0, "x0"); // ID为0，名称为x0

    private final int id;
    // 默认命名规则是 "x" + ID
    private final String name;

    private final int hashCode;

    /**
     * 私有构造函数，用于内部创建和常量定义。
     * 确保所有 Clock 实例都通过工厂方法创建。
     */
    private Clock(int id, String name) {
        this.id = id;
        this.name = name;
        this.hashCode = Objects.hash(id);
        logger.info("创建了一个Clock: {} with id {}", name, id);
    }

    /**
     * 创建一个新的普通时钟。
     * ID 由生成器分配，名称为 "x" + ID。
     * @return 新的 Clock 实例。
     */
    public static Clock createNewClock() {
        int id = NEXT_ID.getAndIncrement();
        return new Clock(id, "x" + id);
    }

    /**
     * 创建一个新的普通时钟，并指定名称。
     * @param name 时钟名称。
     * @return 新的 Clock 实例。
     */
    public static Clock createNewClock(String name) {
        if (name.equals("x0")) {
            logger.warn("名称 'x0' 已被占用，将使用默认名称 'x' + ID。");
            return createNewClock();
        }
        return new Clock(NEXT_ID.getAndIncrement(), name);
    }

    /**
     * 检查此时钟是否为零时钟。
     * @return 如果是零时钟则返回 true。
     */
    public boolean isZeroClock() {
        logger.debug("检查了{}是否为零时钟", this);
        return this.id == 0;
    }

    @Override
    public int compareTo(Clock other) {
        logger.debug("比较了{}和{}", this, other);
        return Integer.compare(this.id, other.id);
    }

    @Override
    public String toString() {
        return this.name;
    }

    @Override
    public int hashCode() {
        logger.debug("请求了{}的哈希码: {}", name, hashCode);
        return hashCode;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        Clock clock = (Clock) obj;
        return this.id == clock.id;
    }

//    private static class IDgenerator {
//        private final AtomicInteger counter = new AtomicInteger(1); // 从1开始，0留给零时钟
//
//        public int createId() {
//            return counter.getAndIncrement();
//        }
//    }
}
