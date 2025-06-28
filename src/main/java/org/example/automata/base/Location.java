package org.example.automata.base; // 放在 automata.base 包下

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 代表定时自动机（PTA）中的一个位置。
 * Location 是不可变对象，一旦创建，其ID和标签就不会改变。
 */
public final class Location implements Comparable<Location> { // 实现 Comparable 方便排序

    private static final Logger logger = LoggerFactory.getLogger(Location.class);

    private static final AtomicInteger NEXT_ID = new AtomicInteger(0); // ID 从 0 开始

    @Getter
    private final int id;
    @Getter
    private final String label;

    private final int hashCode;

    /**
     * 私有构造函数，用于内部创建 Location 实例。
     * 外部应通过工厂方法创建 Location。
     * @param id 位置的唯一ID。
     * @param label 位置的标签/名称。
     */
    private Location(int id, String label) {
        this.id = id;
        this.label = Objects.requireNonNull(label, "Location label cannot be null");
        this.hashCode = Objects.hash(id, label);
        logger.info("创建了一个Location: {} with id {}", label, id);
    }

    /**
     * 创建一个新的普通位置，ID 由生成器分配，标签为 "L" + ID。
     * @return 新的 Location 实例。
     */
    public static Location createNewLocation() {
        int newId = NEXT_ID.getAndIncrement();
        return new Location(newId, "L" + newId);
    }

    /**
     * 创建一个新的普通位置，并指定标签。
     * @param label 位置的标签/名称。
     * @return 新的 Location 实例。
     */
    public static Location createNewLocation(String label) {
        return new Location(NEXT_ID.getAndIncrement(), label);
    }

    public boolean isSink() {
        return "sink".equalsIgnoreCase(label);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Location location = (Location) o;
        return id == location.id;
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return label;
    }

    @Override
    public int compareTo(Location other) {
        return Integer.compare(this.id, other.id);
    }
}
