package org.example.core;

import lombok.Getter;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Getter
public final class Parameter implements Comparable<Parameter>{

    private static final Logger logger = LoggerFactory.getLogger(Parameter.class);

    private static final AtomicInteger NEXT_ID = new AtomicInteger(0); // 从0开始
    // 默认命名规则是 "p" + ID
    private final String name;
    private final int id;
    private final int hashCode;

    private Parameter(int id, String name) {
        this.name = name;
        this.id = id;
        this.hashCode = Objects.hash(id);
        logger.debug("创建了一个Parameter: {} with id {}", name, id);
    }

    public static Parameter createNewParameter() {
        int id = NEXT_ID.getAndIncrement();
        logger.debug("创建了一个新的Parameter: {} with id {}", "p" + id, id);
        return new Parameter(id, "p" + id);
    }

    @Override
    public int compareTo(Parameter o) {
        return Integer.compare(this.id, o.id);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Parameter parameter = (Parameter) o;
        return id == parameter.id;
    }

    @Override
    public int hashCode() {
        logger.debug("请求了{}的哈希码: {}", name, hashCode);
        return hashCode;
    }

    @Override
    public String toString() {
        return name;
    }
}
