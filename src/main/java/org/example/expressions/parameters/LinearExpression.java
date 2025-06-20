package org.example.expressions.parameters; // 放在 expressions.parameters 包下

import com.microsoft.z3.ArithExpr;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import lombok.Getter;
import org.example.core.Parameter;
import org.example.core.ParameterValuation; // 用于求值
import org.example.expressions.ToZ3ArithExpr;
import org.example.symbolic.Z3VariableManager;
import org.example.utils.Rational;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 代表一个只包含参数的线性表达式，形式为 c1*p1 + c2*p2 + ... + const。
 */
@Getter
public final class LinearExpression implements Comparable<LinearExpression>, ToZ3ArithExpr {

    Logger logger = LoggerFactory.getLogger(LinearExpression.class);

    private final SortedMap<Parameter, Rational> coefficients;

    private final Rational constant;

    private final int hashCode;

    /**
     * 私有构造函数。
     * @param coefficients 参数到其系数的映射。
     * @param constant 常数项。
     */
    private LinearExpression(Map<Parameter, Rational> coefficients, Rational constant) {
        // 防御性拷贝并确保有序性，同时过滤掉系数为零的参数
        Map<Parameter, Rational> tempCoefficients = new HashMap<>();
        for (Map.Entry<Parameter, Rational> entry : Objects.requireNonNull(coefficients, "Coefficients map cannot be null").entrySet()) {
            Parameter param = Objects.requireNonNull(entry.getKey(), "Parameter in coefficients map cannot be null");
            Rational coeff = Objects.requireNonNull(entry.getValue(), "Coefficient cannot be null");
            if (!coeff.isZero()) {
                tempCoefficients.put(param, coeff);
            }
        }
        this.coefficients = Collections.unmodifiableSortedMap(new TreeMap<>(tempCoefficients));
        this.constant = Objects.requireNonNull(constant, "Constant term cannot be null");
        this.hashCode = Objects.hash(this.coefficients, this.constant);
        logger.debug("创建 LinearExpression: {}，常数项为{}", this.coefficients, this.constant);
    }

    /**
     * 工厂方法：创建 LinearExpression 实例。
     * @param coefficients 参数到其系数的映射。
     * @param constant 常数项。
     * @return LinearExpression 实例。
     */
    public static LinearExpression of(Map<Parameter, Rational> coefficients, Rational constant) {
        return new LinearExpression(coefficients, constant);
    }

    /**
     * 工厂方法：创建 LinearExpression 实例，常数项为零。
     * @param coefficients 参数到其系数的映射。
     * @return LinearExpression 实例。
     */
    public static LinearExpression of(Map<Parameter, Rational> coefficients) {
        return new LinearExpression(coefficients, Rational.ZERO);
    }

    /**
     * 工厂方法：创建只包含常数项的 LinearExpression 实例。
     * @param constant 常数项。
     * @return LinearExpression 实例。
     */
    public static LinearExpression of(Rational constant) {
        return new LinearExpression(Collections.emptyMap(), constant); // 使用空 Map 表示没有参数
    }

    /**
     * 工厂方法：创建只包含一个参数的 LinearExpression 实例 (例如 p1)。
     * @param parameter 参数。
     * @return LinearExpression 实例。
     */
    public static LinearExpression of(Parameter parameter) {
        return new LinearExpression(Map.of(parameter, Rational.ONE), Rational.ZERO);
    }

    /**
     * 工厂方法：创建只包含一个参数和系数的 LinearExpression 实例 (例如 2*p1)。
     * @param parameter 参数。
     * @param coefficient 系数。
     * @return LinearExpression 实例。
     */
    public static LinearExpression of(Parameter parameter, Rational coefficient) {
        return new LinearExpression(Map.of(parameter, coefficient), Rational.ZERO);
    }


    /**
     * 将此表达式与另一个表达式相加。
     * @param other 另一个 LinearExpression。
     * @return 相加后的新 LinearExpression。
     */
    public LinearExpression add(LinearExpression other) {
        Map<Parameter, Rational> newCoefficients = new HashMap<>(this.coefficients);
        other.coefficients.forEach((para, value) -> {
            newCoefficients.merge(para, value, Rational::add);
        });
        logger.debug("计算了{}和{}的和", this, other);
        return new LinearExpression(newCoefficients, this.constant.add(other.constant));
    }

    /**
     * 将此表达式减去另一个表达式。
     * @param other 另一个 LinearExpression。
     * @return 相减后的新 LinearExpression。
     */
    public LinearExpression subtract(LinearExpression other) {
        Map<Parameter, Rational> newCoefficients = new HashMap<>(this.coefficients);
        other.coefficients.forEach((para, value) -> {
            newCoefficients.merge(para, value.negate(), Rational::add); // 减去相当于加上负数
        });
        logger.debug("计算了{}和{}的差", this, other);
        return new LinearExpression(newCoefficients, this.constant.subtract(other.constant));
    }

    /**
     * 对此表达式取反 (乘以 -1)。
     * @return 取反后的新 LinearExpression。
     */
    public LinearExpression negate() {
        Map<Parameter, Rational> negatedCoefficients = this.coefficients.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().negate()));
        logger.debug("计算了{}的相反数", this);
        return new LinearExpression(negatedCoefficients, this.constant.negate());
    }

    /**
     * 根据给定的参数赋值，计算表达式的具体数值。
     * @param parameterValuation 参数赋值。
     * @return 表达式的 Rational 值。
     */
    public Rational evaluate(ParameterValuation parameterValuation) {
        Rational result = this.constant;
        for (Map.Entry<Parameter, Rational> entry : coefficients.entrySet()) {
            Parameter param = entry.getKey();
            Rational coeff = entry.getValue();
            Rational paramValue = parameterValuation.getValue(param); // 获取参数的具体值
            result = result.add(coeff.multiply(paramValue));
        }
        logger.debug("根据参数赋值{}计算了{}的结果为{}", parameterValuation, this, result);
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        boolean firstTerm = true;

        // 遍历有序的系数，确保输出顺序稳定
        for (Map.Entry<Parameter, Rational> entry : coefficients.entrySet()) {
            Parameter param = entry.getKey();
            Rational coeff = entry.getValue();

            if (coeff.isZero()) {
                continue;
            }

            if (!firstTerm) {
                sb.append(" + ");
            }
            sb.append(coeff);
            sb.append("*");
            sb.append(param.getName());
            firstTerm = false;
        }

        if (!constant.isZero()) {
            if (!firstTerm && constant.signum() > 0) { // 如果前面有项且常数项为正
                sb.append(" + ");
            } else if (constant.signum() < 0) { // 如果常数项为负，直接追加
                // Rational 的 toString 应该处理负号
            }
            sb.append(constant);
        } else if (firstTerm) { // 如果所有系数和常数项都为零
            return "0";
        }

        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        LinearExpression that = (LinearExpression) o;
        return coefficients.equals(that.coefficients) && constant.equals(that.constant);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public ArithExpr toZ3ArithExpr(Context ctx, Z3VariableManager varManager) {
        ArithExpr result = constant.toZ3Real(ctx);
        for (Map.Entry<Parameter, Rational> entry : coefficients.entrySet()) {
            Parameter param = entry.getKey();
            Rational coeff = entry.getValue();
            ArithExpr paramExpr = varManager.getZ3Var(param);
            ArithExpr z3Coeff = coeff.toZ3Real(ctx);
            result = ctx.mkAdd(result, ctx.mkMul(z3Coeff, paramExpr));
        }
        return result;
    }

    @Override
    public int compareTo(LinearExpression other) {
        // 1. 比较常数项
        int cmp = this.constant.compareTo(other.constant);
        if (cmp != 0) {
            return cmp;
        }

        // 2. 比较系数 Map (按参数 ID 顺序遍历)
        // 获取所有涉及到的参数，并排序
        Set<Parameter> allParameters = new TreeSet<>(Comparator.comparingInt(Parameter::getId));
        allParameters.addAll(this.coefficients.keySet());
        allParameters.addAll(other.coefficients.keySet());

        for (Parameter param : allParameters) {
            Rational thisCoeff = this.coefficients.getOrDefault(param, Rational.ZERO);
            Rational otherCoeff = other.coefficients.getOrDefault(param, Rational.ZERO);
            cmp = thisCoeff.compareTo(otherCoeff);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }
}
