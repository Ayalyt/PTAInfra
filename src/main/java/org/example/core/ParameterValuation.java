package org.example.core;

import lombok.Getter;
import org.example.utils.Rational;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Ayalyt
 */
@Getter
public final class ParameterValuation implements Comparable<ParameterValuation>{

    private static final Logger logger = LoggerFactory.getLogger(ParameterValuation.class);
    /**
     * @return Map<Parameter, Rational>。
     */
    private final SortedMap<Parameter, Rational> parameterValuation;

    /**
     * 私有构造函数，通过 Map 创建 ParameterValuation。
     * @param parameterValuation 包含参数及其值的 Map。
     */
    private ParameterValuation(Map<Parameter, Rational> parameterValuation) {
        logger.info("创建 ParameterValuation: {}", parameterValuation);
        this.parameterValuation = Collections.unmodifiableSortedMap(new TreeMap<>(parameterValuation));
    }

    /**
     * 工厂方法：从 Map 创建 ParameterValuation 实例。
     * @param values Map<Parameter, Rational>，包含参数及其值。
     * @return ParameterValuation 实例。
     * @throws IllegalArgumentException 如果任何参数值不是有限的非负数。
     */
    public static ParameterValuation of(Map<Parameter, Rational> values) {
        return new ParameterValuation(values);
    }


    /**
     * 工厂方法：创建一个全零的 ParameterValuation。
     * @param paras 要创建全零的时钟集合。
     * @return 全零的 ParameterValuation。
     */
    public static ParameterValuation zero(Set<Parameter> paras) {
        Map<Parameter, Rational> zeroValues = new HashMap<>();
        for (Parameter para : paras) {
            zeroValues.put(para, Rational.ZERO);
        }
        logger.info("对于参数列表{}创建零ParameterValuation", paras);
        return new ParameterValuation(zeroValues);
    }


    /**
     * 获取指定参数的值。
     * @param para 要获取值的参数。
     * @return 参数的 Rational 值。
     */
    public Rational getValue(Parameter para) {
        if (!parameterValuation.containsKey(para)) {
            logger.error("尝试获取不存在的参数值：参数 '{}' 不存在于当前赋值 {} 中。", para.getName(), this);
            throw new IllegalArgumentException("参数 '" + para.getName() + "' 不存在于当前赋值中。");
        }
        return parameterValuation.get(para);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ParameterValuation that = (ParameterValuation) o;
        return parameterValuation.equals(that.parameterValuation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(parameterValuation);
    }

    @Override
    public String toString() {
        return "{" +
                parameterValuation.entrySet().stream()
                        .map(entry -> entry.getKey().getName() + "=" + entry.getValue())
                        .collect(Collectors.joining(", ")) +
                "}";
    }

    @Override
    public int compareTo(ParameterValuation other) {
        // 获取所有涉及到的参数，并排序 (确保比较所有相关参数，即使某个 valuation 中没有显式赋值)
        Set<Parameter> allParameters = new TreeSet<>(Comparator.comparingInt(Parameter::getId));
        allParameters.addAll(this.parameterValuation.keySet());
        allParameters.addAll(other.parameterValuation.keySet());
        logger.debug("比较{}和{}", this, other);

        for (Parameter para : allParameters) {
            Rational thisValue = this.getValue(para);
            Rational otherValue = other.getValue(para);

            int comparison = thisValue.compareTo(otherValue);
            if (comparison != 0) {
                logger.debug("由于{}和{}中{}对于值的比较，结果为{}", this, other, para, comparison);
                return comparison;
            }
        }
        return 0;
    }
}
