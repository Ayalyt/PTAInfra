package org.example.automata.base; // 放在 automata.base 包下

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger; // 如果需要全局唯一ID

@Getter
public final class Action implements Comparable<Action> {

    private static final Logger logger = LoggerFactory.getLogger(Action.class);

    // 定义 epsilon 动作的常量
    public static final Action EPSILON = new Action(""); // 空字符串表示 epsilon 动作

    private static final AtomicInteger NEXT_ID = new AtomicInteger(0); // ID 从 0 开始


    private final int id;

    private final String label;
    private final boolean isEpsilon;

    private final int hashCode;

    /**
     * 私有构造函数，用于内部创建 Action 实例。
     * 外部应通过工厂方法创建 Action。
     * @param label 动作的标签。
     */
    private Action(String label) {
        this.label = Objects.requireNonNull(label, "Action label cannot be null");
        this.isEpsilon = label.isEmpty();
        this.id = NEXT_ID.getAndIncrement();
        this.hashCode = Objects.hash(label);
        logger.debug("创建 Action: {}", label);
    }

    /**
     * 工厂方法：创建或获取一个动作标签。
     * 建议使用此方法创建所有 Action 实例，以便未来可以引入缓存机制。
     * @param label 动作的标签。
     * @return 对应的 Action 实例。
     */
    public static Action of(String label) {
        if (label == null || label.isEmpty()) {
            return EPSILON;
        }
        return new Action(label);
    }

    /**
     * 检查此动作是否为 epsilon 动作。
     * @return 如果是 epsilon 动作则返回 true。
     */
    public boolean isEpsilon() {
        return isEpsilon;
    }

    @Override
    public String toString() {
        return label.isEmpty() ? "ε" : label;
    }

    @Override
    public int compareTo(Action other) {
        // 比较规则：epsilon 动作通常排在最前面
        if (this.isEpsilon() && !other.isEpsilon()) {
            return -1;
        }
        if (!this.isEpsilon() && other.isEpsilon()) {
            return 1;
        }
        // 如果都是 epsilon 或都不是 epsilon，则按标签字母顺序比较
        return this.label.compareTo(other.label);
        // 如果引入了 ID，则可以按 ID 比较：return Integer.compare(this.id, other.id);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Action action1 = (Action) o;
        // 比较标签和是否为 epsilon 动作
        // 如果引入了 ID，则只比较 ID：return id == action1.id;
        return isEpsilon == action1.isEpsilon && label.equals(action1.label);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }
}
