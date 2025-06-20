package org.example.automata.base;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 代表一个定时自动机（PTA）的动作集合（字母表）。
 * Alphabet 是不可变对象，一旦创建，其包含的动作集合就不会改变。
 * 它负责管理和提供 PTA 中使用的所有 Action 实例。
 */
@Getter
public final class Alphabet {

    private static final Logger logger = LoggerFactory.getLogger(Alphabet.class);

    @Getter
    private final SortedSet<Action> actions;
    private final Map<String, Action> actionsByLabel;
    private final Map<Integer, Action> actionsById;
    private final int hashCode;

    /**
     * 私有构造函数，通过动作集合创建 Alphabet。
     * @param actions 包含所有动作的集合。
     */
    private Alphabet(Set<Action> actions) {
        Objects.requireNonNull(actions, "Actions set cannot be null");

        // 确保包含 EPSILON 动作
        if (!actions.contains(Action.EPSILON)) {
            Set<Action> tempActions = new HashSet<>(actions);
            tempActions.add(Action.EPSILON);
            actions = tempActions;
            logger.debug("Alphabet 构造时自动添加了 EPSILON 动作。");
        }

        // 初始化内部存储
        this.actions = Collections.unmodifiableSortedSet(new TreeSet<>(actions));
        this.actionsByLabel = Collections.unmodifiableMap(
                actions.stream().collect(Collectors.toMap(Action::getLabel, action -> action))
        );
        this.actionsById = Collections.unmodifiableMap(
                actions.stream().collect(Collectors.toMap(Action::getId, action -> action))
        );

        // 检查标签唯一性（如果 Action.of(label) 每次都创建新 ID，则标签可能重复）
        // 如果 Action.of(label) 每次都创建新 ID，那么 actionsByLabel 可能会有冲突
        // 假设 Action.of(label) 每次都创建新 ID，那么 Alphabet 应该只接受唯一的标签
        if (actions.size() != actionsByLabel.size()) {
            logger.warn("Alphabet 包含重复的动作标签。请确保每个标签只对应一个 Action 实例。");
            throw new IllegalArgumentException("Alphabet 包含重复的动作标签。");
        }

        this.hashCode = Objects.hash(actions);
        logger.debug("创建 Alphabet，包含 {} 个动作。详情：{}", this.actions.size(), this.actions);
    }

    /**
     * 工厂方法：从一个动作集合创建 Alphabet 实例。
     * @param actions 构成字母表的动作集合。
     * @return Alphabet 实例。
     */
    public static Alphabet of(Set<Action> actions) {
        return new Alphabet(actions);
    }

    /**
     * 工厂方法：从一系列动作标签创建 Alphabet 实例。
     * 此方法会为每个标签创建新的 Action 实例（如果不存在）。
     * @param labels 动作标签的数组或可变参数。
     * @return Alphabet 实例。
     */
    public static Alphabet of(String... labels) {
        Set<Action> actionSet = new HashSet<>();
        for (String label : labels) {
            actionSet.add(Action.of(label));
        }
        return new Alphabet(actionSet);
    }

    /**
     * 根据标签获取动作。
     * @param label 动作标签。
     * @return 对应的 Action 实例，如果不存在则返回 null。
     */
    public Action getActionByLabel(String label) {
        logger.debug("请求了动作标签 {} 的动作", label);
        return actionsByLabel.get(label);
    }

    /**
     * 根据 ID 获取动作。
     * @param id 动作 ID。
     * @return 对应的 Action 实例，如果不存在则返回 null。
     */
    public Action getActionById(int id) {
        logger.debug("请求了 ID {} 的动作", id);
        return actionsById.get(id);
    }

    /**
     * 检查字母表是否包含某个动作。
     * @param action 要检查的动作。
     * @return 如果包含则返回 true。
     */
    public boolean contains(Action action) {
        logger.debug("检查字母表{}是否包含动作 {}", this, action);
        return actions.contains(action);
    }

    /**
     * 获取字母表中动作的数量。
     * @return 动作数量。
     */
    public int size() {
        logger.debug("请求了字母表的大小：{}", actions.size());
        return actions.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Alphabet alphabet = (Alphabet) o;
        return actions.equals(alphabet.actions);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return "Alphabet{" +
                actions.stream()
                        .map(Action::toString)
                        .collect(Collectors.joining(", ")) +
                '}';
    }
}
