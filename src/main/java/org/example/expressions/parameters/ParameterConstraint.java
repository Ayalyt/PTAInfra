package org.example.expressions.parameters; // 放在 expressions.parameters 包下

import com.microsoft.z3.ArithExpr;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import org.example.expressions.RelationType;
import org.example.expressions.ToZ3BoolExpr;
import org.example.symbolic.Z3VariableManager;
import org.example.utils.Rational;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 代表一个纯粹的参数约束，形式为 E1 ~ E2，其中 E1 和 E2 都是含参线性表达式。
 * 内部规范化为 E_normalized ~ 0 的形式。
 * 此类是不可变的。
 */
public final class ParameterConstraint implements Comparable<ParameterConstraint>, ToZ3BoolExpr {

    private static final Logger logger = LoggerFactory.getLogger(ParameterConstraint.class);

    // 规范化后的形式：leftExpr ~ 0
    private final LinearExpression leftExpr; // 规范化后的左侧表达式 (E1 - E2)
    private final RelationType relation;     // 规范化后的关系类型 (~')

    private final int hashCode;

    /**
     * 私有构造函数，用于创建 ParameterConstraint 实例。
     * 内部会进行规范化：将 E1 ~ E2 转换为 E_normalized ~ 0。
     *
     * @param left     原始左侧表达式。
     * @param right    原始右侧表达式。
     * @param relation 原始关系类型。
     * @throws NullPointerException 如果任何参数为 null。
     */
    private ParameterConstraint(LinearExpression left, LinearExpression right, RelationType relation) {
        Objects.requireNonNull(left, "ParameterConstraint-构造函数: left 表达式不能为 null");
        Objects.requireNonNull(right, "ParameterConstraint-构造函数: right 表达式不能为 null");
        Objects.requireNonNull(relation, "ParameterConstraint-构造函数: relation 不能为 null");

        // 规范化为 E_normalized ~ 0 的形式
        // E1 ~ E2  =>  (E1 - E2) ~ 0
        this.leftExpr = left.subtract(right);
        this.relation = relation; // 关系类型保持不变，因为只是移项

        // 检查自身矛盾或恒真 (可选，Z3 也会处理)
        // 如果 leftExpr 是一个常数 (没有参数)
        if (this.leftExpr.getCoefficients().isEmpty()) {
            Rational constantValue = this.leftExpr.getConstant();
            boolean isTrue = false;
            boolean isFalse = false;

            switch (this.relation) {
                case LT: isTrue = constantValue.compareTo(Rational.ZERO) < 0; isFalse = constantValue.compareTo(Rational.ZERO) >= 0; break;
                case LE: isTrue = constantValue.compareTo(Rational.ZERO) <= 0; isFalse = constantValue.compareTo(Rational.ZERO) > 0; break;
                case GT: isTrue = constantValue.compareTo(Rational.ZERO) > 0; isFalse = constantValue.compareTo(Rational.ZERO) <= 0; break;
                case GE: isTrue = constantValue.compareTo(Rational.ZERO) >= 0; isFalse = constantValue.compareTo(Rational.ZERO) < 0; break;
                default: break; // TODO: 有其他类型再说
            }

            if (isFalse) {
                logger.warn("ParameterConstraint-构造函数: 创建了一个恒假约束: {}", this);
            } else if (isTrue) {
                logger.info("ParameterConstraint-构造函数: 创建了一个恒真约束: {}", this);
            }
        }
        this.hashCode = Objects.hash(this.leftExpr, this.relation);
        logger.info("创建 ParameterConstraint: {}", this);
    }

    /**
     * 工厂方法：创建 ParameterConstraint 实例。
     * @param left     左侧表达式。
     * @param right    右侧表达式。
     * @param relation 关系类型。
     * @return ParameterConstraint 实例。
     */
    public static ParameterConstraint of(LinearExpression left, LinearExpression right, RelationType relation) {
        return new ParameterConstraint(left, right, relation);
    }

    /**
     * 取反当前参数约束。
     * @return 取反后的新 ParameterConstraint。
     */
    public ParameterConstraint negate() {
        // ¬(E_normalized ~ 0) => E_normalized ~' 0
        return new ParameterConstraint(this.leftExpr, LinearExpression.of(Rational.ZERO), this.relation.negate());
    }

    @Override
    public BoolExpr toZ3BoolExpr(Context ctx, Z3VariableManager varManager) {
        ArithExpr z3LeftExpr = leftExpr.toZ3ArithExpr(ctx, varManager);
        ArithExpr z3Zero = ctx.mkReal(0);

        return switch (relation) {
            case LT -> ctx.mkLt(z3LeftExpr, z3Zero);
            case LE -> ctx.mkLe(z3LeftExpr, z3Zero);
            case GT -> ctx.mkGt(z3LeftExpr, z3Zero);
            case GE -> ctx.mkGe(z3LeftExpr, z3Zero);
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
        ParameterConstraint that = (ParameterConstraint) o;
        // 由于构造函数已规范化，直接比较字段即可
        return relation == that.relation && leftExpr.equals(that.leftExpr);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return leftExpr.toString() + " " + relation.getSymbol() + " 0";
    }

    @Override
    public int compareTo(ParameterConstraint other) {
        int cmp = leftExpr.compareTo(other.leftExpr);
        if (cmp != 0) {
            return cmp;
        }
        return relation.compareTo(other.relation);
    }
}
