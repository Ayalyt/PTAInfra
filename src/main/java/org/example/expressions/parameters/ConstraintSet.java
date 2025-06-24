package org.example.expressions.parameters; // 放在 expressions.parameters 包下

import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import lombok.Getter;
import org.example.expressions.ToZ3BoolExpr;
import org.example.symbolic.Z3VariableManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 代表一个参数约束的集合，其语义为集合中所有约束的合取。
 * 定义了参数空间中的一个凸多面体区域。
 * 此类是不可变的。
 */
@Getter
public final class ConstraintSet implements Comparable<ConstraintSet>, ToZ3BoolExpr {

    private static final Logger logger = LoggerFactory.getLogger(ConstraintSet.class);

    // 内部存储 ParameterConstraint 的有序集合，确保规范化和比较的一致性
    private final SortedSet<ParameterConstraint> constraints;

    // 预定义常量：表示恒真的空约束集
    public static final ConstraintSet TRUE_CONSTRAINT_SET = new ConstraintSet(Collections.emptySet());
    // FALSE_CONSTRAINT_SET 不好定义。目前的方案是尽可能避免直接需求恒假量的情况，一切交由Oracle判断
    private final int hashCode;

    /**
     * 私有构造函数，用于创建 ConstraintSet 实例。
     * @param constraints 包含 ParameterConstraint 的集合。
     */
    private ConstraintSet(Set<ParameterConstraint> constraints) {
        Objects.requireNonNull(constraints, "Constraints set cannot be null");
        // 防御性拷贝并确保有序性
        this.constraints = Collections.unmodifiableSortedSet(new TreeSet<>(constraints));
        logger.debug("创建 ConstraintSet: {}", this);
        // 由于内部是 SortedSet，直接计算集合的哈希码即可
        this.hashCode = Objects.hash(this.constraints);
    }

    /**
     * 工厂方法：从一个 ParameterConstraint 集合创建 ConstraintSet 实例。
     * @param constraints 构成约束集的 ParameterConstraint 集合。
     * @return ConstraintSet 实例。
     */
    public static ConstraintSet of(Set<ParameterConstraint> constraints) {
        return new ConstraintSet(constraints);
    }

    /**
     * 工厂方法：从单个 ParameterConstraint 创建 ConstraintSet 实例。
     * @param constraint 单个 ParameterConstraint。
     * @return 包含该约束的 ConstraintSet 实例。
     */
    public static ConstraintSet of(ParameterConstraint constraint) {
        return new ConstraintSet(Collections.singleton(constraint));
    }

    /**
     * 将此约束集与另一个 ParameterConstraint 进行合取。
     * @param otherConstraint 另一个 ParameterConstraint。
     * @return 合取后的新 ConstraintSet。
     */
    public ConstraintSet and(ParameterConstraint otherConstraint) {
        Set<ParameterConstraint> newConstraints = new TreeSet<>(this.constraints);
        newConstraints.add(otherConstraint);
        logger.debug("对ConstraintSet{}和Constraint{}进行合取", this, otherConstraint);
        return new ConstraintSet(newConstraints);
    }

    /**
     * 将此约束集与另一个 ConstraintSet 进行合取。
     * @param otherSet 另一个 ConstraintSet。
     * @return 合取后的新 ConstraintSet。
     */
    public ConstraintSet and(ConstraintSet otherSet) {
        Set<ParameterConstraint> newConstraints = new TreeSet<>(this.constraints);
        newConstraints.addAll(otherSet.constraints);
        logger.debug("对ConstraintSet{}和ConstraintSet{}进行合取", this, otherSet);
        return new ConstraintSet(newConstraints);
    }

    /**
     * 检查此约束集是否为空（即不包含任何约束）。
     * 空约束集在语义上表示恒真。
     * @return true 如果为空，false 否则。
     */
    public boolean isEmpty() {
        return constraints.isEmpty();
    }

    // --- Z3 转换 ---
    @Override
    public BoolExpr toZ3BoolExpr(Context ctx, Z3VariableManager varManager) {
        if (constraints.isEmpty()) {
            return ctx.mkTrue(); // 空约束集表示恒真
        }
        BoolExpr[] z3Constraints = constraints.stream()
                .map(c -> c.toZ3BoolExpr(ctx, varManager))
                .toArray(BoolExpr[]::new);
        return ctx.mkAnd(z3Constraints);
    }

    // --- Object 方法 ---
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ConstraintSet that = (ConstraintSet) o;
        // 由于内部是 SortedSet，直接比较集合即可
        return constraints.equals(that.constraints);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        if (constraints.isEmpty()) {
            return "TRUE"; // 语义上表示恒真
        }
        return "(" +
                constraints.stream()
                        .map(ParameterConstraint::toString)
                        .collect(Collectors.joining(" /\\ ")) +
                ")";
    }

    @Override
    public int compareTo(ConstraintSet other) {
        // 比较两个集合，按元素顺序逐个比较
        Iterator<ParameterConstraint> thisIt = this.constraints.iterator();
        Iterator<ParameterConstraint> otherIt = other.constraints.iterator();

        while (thisIt.hasNext() && otherIt.hasNext()) {
            ParameterConstraint thisC = thisIt.next();
            ParameterConstraint otherC = otherIt.next();
            int cmp = thisC.compareTo(otherC);
            if (cmp != 0) {
                return cmp;
            }
        }
        // 如果一个集合是另一个集合的前缀，则较长的集合更大
        return Integer.compare(this.constraints.size(), other.constraints.size());
    }
}
