package org.example.expressions.dcs;

import com.microsoft.z3.ArithExpr;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import lombok.Getter;
import org.example.core.Clock;
import org.example.expressions.RelationType;
import org.example.expressions.ToZ3BoolExpr;
import org.example.expressions.parameters.LinearExpression;
import org.example.symbolic.Z3VariableManager;
import org.example.utils.Rational;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 代表一个原子时钟差分约束 (Simple Clock Guard), 形如 c1 - c2 ~ E。
 * 其中 E 是一个含参线性表达式。
 * 这是 PDBM 的基本构建块。
 * 此类是不可变的。
 * @author Ayalyt
 */
@Getter
public final class AtomicGuard implements Comparable<AtomicGuard>, ToZ3BoolExpr {

    private static final Logger logger = LoggerFactory.getLogger(AtomicGuard.class);

    private final Clock clock1;       // 第一个时钟 (c_i)
    private final Clock clock2;       // 第二个时钟 (c_j)
    private final LinearExpression bound; // 边界值 (E), 必须是 LinearExpression
    private final RelationType relation; // 关系类型 (<, <=, >, >=)

    /**
     * 私有构造函数，用于创建原子时钟差分约束。
     * 内部会进行规范化：确保 clock1.id <= clock2.id。
     *
     * @param c1       第一个时钟。
     * @param c2       第二个时钟。
     * @param bound    边界表达式。
     * @param relation 关系类型。
     * @throws NullPointerException     如果任何参数为 null。
     * @throws IllegalArgumentException 如果约束自身矛盾 (例如 x - x < 0)。
     */
    private AtomicGuard(Clock c1, Clock c2, LinearExpression bound, RelationType relation) {
        Objects.requireNonNull(c1, "AtomicGuard-构造函数: clock1 不能为 null");
        Objects.requireNonNull(c2, "AtomicGuard-构造函数: clock2 不能为 null");
        Objects.requireNonNull(bound, "AtomicGuard-构造函数: bound 不能为 null");
        Objects.requireNonNull(relation, "AtomicGuard-构造函数: relation 不能为 null");

        // 规范化时钟顺序：确保 clock1.id <= clock2.id
        if (c1.getId() > c2.getId()) {
            // 交换时钟，并调整边界和关系
            this.clock1 = c2;
            this.clock2 = c1;
            this.bound = bound.negate(); // E -> -E
            this.relation = relation.flip(); // < -> >, <= -> >=, > -> <, >= -> <=
        } else {
            this.clock1 = c1;
            this.clock2 = c2;
            this.bound = bound;
            this.relation = relation;
        }
        logger.debug("创建了一个 AtomicGuard: {}", this);
        // 检查自身矛盾 (在规范化后检查)
        if (this.clock1.equals(this.clock2)) { // 形式为 x - x ~ E
            // 此时，E 必须是常数，否则无法判断矛盾
            if (!this.bound.getCoefficients().isEmpty()) {
                logger.warn("AtomicGuard-构造函数: 约束 {} - {} {} {} 包含自身时钟差分，但边界表达式包含参数。这可能导致无法在构造时判断矛盾。",
                        this.clock1.getName(), this.clock2.getName(), this.relation.getSymbol(), this.bound);
                // 这种情况下，矛盾判断需要 Z3 在运行时进行
            } else {
                // 边界是常数，可以判断矛盾
                Rational constantBound = this.bound.getConstant();
                int comparison = constantBound.compareTo(Rational.ZERO);
                // x - x < V (V <= 0) -> 矛盾 (例如 x-x < 0, x-x < -5)
                // x - x <= V (V < 0) -> 矛盾 (例如 x-x <= -1)
                // x - x > V (V >= 0) -> 矛盾 (例如 x-x > 0, x-x > 5)
                // x - x >= V (V > 0) -> 矛盾 (例如 x-x >= 1)
                if ((this.relation == RelationType.LT && comparison <= 0) ||
                        (this.relation == RelationType.LE && comparison < 0) ||
                        (this.relation == RelationType.GT && comparison >= 0) ||
                        (this.relation == RelationType.GE && comparison > 0)) {
                    logger.error("AtomicGuard-构造函数: 约束 {} - {} {} {} 自身矛盾",
                            this.clock1.getName(), this.clock2.getName(), this.relation.getSymbol(), this.bound.toString());
                    throw new IllegalArgumentException("AtomicGuard-构造函数: 约束 " + this + " 自身矛盾");
                }
            }
        }
    }

    // --- 工厂方法 ---
    public static AtomicGuard of(Clock c1, Clock c2, LinearExpression bound, RelationType relation) {
        return new AtomicGuard(c1, c2, bound, relation);
    }

    public static AtomicGuard lessThan(Clock c, Rational value) { return new AtomicGuard(c, Clock.ZERO_CLOCK, LinearExpression.of(value), RelationType.LT); }
    public static AtomicGuard lessEqual(Clock c, Rational value) { return new AtomicGuard(c, Clock.ZERO_CLOCK, LinearExpression.of(value), RelationType.LE); }
    public static AtomicGuard greaterThan(Clock c, Rational value) { return new AtomicGuard(c, Clock.ZERO_CLOCK, LinearExpression.of(value), RelationType.GT); }
    public static AtomicGuard greaterEqual(Clock c, Rational value) { return new AtomicGuard(c, Clock.ZERO_CLOCK, LinearExpression.of(value), RelationType.GE); }

    /**
     * 取反当前原子约束。
     * @return 取反后的新 AtomicGuard。
     */
    public AtomicGuard negate() {
        // 这里是对整个不等式进行逻辑否定，不涉及操作数交换
        return new AtomicGuard(this.clock1, this.clock2, this.bound, this.relation.negate());
    }

    // --- Z3 转换 ---
    @Override
    public BoolExpr toZ3BoolExpr(Context ctx, Z3VariableManager varManager) {
        ArithExpr z3Clock1 = varManager.getZ3Var(clock1);
        ArithExpr z3Clock2 = varManager.getZ3Var(clock2);
        ArithExpr z3Bound = bound.toZ3ArithExpr(ctx, varManager);

        ArithExpr diffZ3 = ctx.mkSub(z3Clock1, z3Clock2);

        return switch (relation) {
            case LT -> ctx.mkLt(diffZ3, z3Bound);
            case LE -> ctx.mkLe(diffZ3, z3Bound);
            case GT -> ctx.mkGt(diffZ3, z3Bound);
            case GE -> ctx.mkGe(diffZ3, z3Bound);
        };
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
        AtomicGuard that = (AtomicGuard) o;
        // 构造函数已规范化，直接比较字段即可
        return relation == that.relation &&
                clock1.equals(that.clock1) &&
                clock2.equals(that.clock2) &&
                bound.equals(that.bound); // 比较 LinearExpression
    }

    @Override
    public int hashCode() {
        // 由于构造函数已规范化，直接计算哈希码即可
        return Objects.hash(clock1, clock2, bound, relation);
    }

    @Override
    public String toString() {
        String op = relation.getSymbol();
        if (clock2.isZeroClock()) { // 形式为 clock1 - x0 ~ bound => clock1 ~ bound
            return clock1.getName() + " " + op + " " + bound.toString();
        } else { // 形式为 clock1 - clock2 ~ bound
            return clock1.getName() + " - " + clock2.getName() + " " + op + " " + bound.toString();
        }
    }


    @Override
    public int compareTo(AtomicGuard other) {
        int cmp = clock1.compareTo(other.clock1);
        if (cmp != 0) {
            return cmp;
        }
        cmp = clock2.compareTo(other.clock2);
        if (cmp != 0) {
            return cmp;
        }
        cmp = bound.compareTo(other.bound);
        if (cmp != 0) {
            return cmp;
        }
        return relation.compareTo(other.relation);
    }
}
