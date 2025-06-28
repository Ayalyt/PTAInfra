package org.example.expressions.dcs;

import lombok.Getter;
import org.apache.commons.lang3.tuple.Pair;
import org.example.automata.base.ResetSet;
import org.example.core.Clock;
import org.example.core.Parameter;
import org.example.expressions.RelationType;
import org.example.expressions.parameters.ConstraintSet;
import org.example.expressions.parameters.LinearExpression;
import org.example.symbolic.Z3Oracle;
import org.example.utils.Rational;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 代表受约束的参数化差分界限矩阵 (Constrained Parametric Difference-Bound Matrix, CPDBM)。
 * CPDBM 是一个对 (C, D)，其中 C 是参数约束集合，D 是 PDBM。
 * 不可变。
 */
@Getter
public final class CPDBM implements Comparable<CPDBM> {

    private static final Logger logger = LoggerFactory.getLogger(CPDBM.class);

    private final ConstraintSet constraintSet; // C
    private final PDBM pdbm;                   // D

    private final int hashCode;

    /**
     * 构造函数。
     * @param constraintSet 参数约束集合。
     * @param pdbm PDBM 实例。
     */
    public CPDBM(ConstraintSet constraintSet, PDBM pdbm) {
        this.constraintSet = Objects.requireNonNull(constraintSet, "ConstraintSet cannot be null.");
        this.pdbm = Objects.requireNonNull(pdbm, "PDBM cannot be null.");
        this.hashCode = Objects.hash(constraintSet, pdbm);
        logger.info("创建 CPDBM: (C: {}, D: \n{})", constraintSet, pdbm);
    }

    /**
     * 将指定的 AtomicGuard 合取到当前 CPDBM 中。
     * 此操作可能导致 CPDBM 分裂，因此返回一个 CPDBM 集合。
     *
     * @param newGuard 要合取的 AtomicGuard。
     * @param oracle Z3 Oracle 实例。
     * @return 包含一个或多个新 CPDBM 的集合。如果导致不可满足，返回空集合。
     */
    public Set<CPDBM> addGuard(AtomicGuard newGuard, Z3Oracle oracle) {
        logger.info("CPDBM.addGuard: 尝试添加守卫 {} 到\n {}", newGuard, this);
        // 调用 PDBM 的 addGuard 方法，它会返回 (ConstraintSet, PDBM) 对的列表
        List<CPDBM> pdbmResults = this.pdbm.addGuard(newGuard, this.constraintSet, oracle);

        Set<CPDBM> resultCPDBMs = pdbmResults.stream()
                .filter(cpdbm -> !cpdbm.isEmpty(oracle)) // 过滤掉语义为空的 CPDBM
                .collect(Collectors.toSet());

        logger.info("CPDBM.addGuard: 返回 {} 个 CPDBM。", resultCPDBMs.size());
        return resultCPDBMs;
    }

    /**
     * 将当前 CPDBM 转换为规范形式。
     * 此操作可能导致 CPDBM 分裂，因此返回一个 CPDBM 集合。
     *
     * @param oracle Z3 Oracle 实例。
     * @return 包含一个或多个规范化 CPDBM 的集合。如果导致不可满足，返回空集合。
     */
    public Set<CPDBM> canonical(Z3Oracle oracle) {
        logger.info("CPDBM.canonical: 规范化 \n{}", this);
        // 调用 PDBM 内部的 canonical 方法，它会返回 (ConstraintSet, PDBM) 对的列表
        List<CPDBM> pdbmResults = this.pdbm.canonical(this.constraintSet, oracle);

        // 将 PDBM 返回的对转换为 CPDBM 集合
        Set<CPDBM> canonicalCPDBMs = pdbmResults.stream()
                .filter(cpdbm -> !cpdbm.isEmpty(oracle)) // 过滤掉语义为空的 CPDBM
                .collect(Collectors.toSet());

        logger.info("CPDBM.canonical: 返回 {} 个规范化 CPDBM。", canonicalCPDBMs.size());
        return canonicalCPDBMs;
    }

    /**
     * 应用时间流逝操作到当前 CPDBM。
     *
     * @return 应用时间流逝后的新 CPDBM 实例。
     */
    public CPDBM delay() {
        logger.info("CPDBM.delay: 应用时间流逝到 {}", this);
        PDBM delayedPDBM = this.pdbm.delay();
        return new CPDBM(this.constraintSet, delayedPDBM);
    }

    /**
     * 应用时钟重置操作到当前 CPDBM。
     *
     * @param resetSet 要重置的时钟集合及其值。
     * @return 应用重置后的新 CPDBM 实例。
     */
    public CPDBM reset(ResetSet resetSet) {
        logger.info("CPDBM.reset: 应用重置 {} 到 \n{}", resetSet, this);
        PDBM resetPDBM = this.pdbm.reset(resetSet);
        return new CPDBM(this.constraintSet, resetPDBM);
    }

    // TODO: 功能上没做异常链，此外效率很差。
    public List<CPDBM> addGuardAndCanonical(AtomicGuard newGuard, Z3Oracle oracle) {

        // 添加初始不变式
        Set<CPDBM> afterInvariantPairs = this.addGuard(
                newGuard,
                oracle
        );

        // 规范化所有PDBM
        List<CPDBM> canonicalPairs = new ArrayList<>();
        for (CPDBM entry : afterInvariantPairs) {
            Set<CPDBM> canonicalized = entry.canonical(oracle);
            canonicalPairs.addAll(canonicalized);
        }

        // 过滤已经不可满足的PDBM
        canonicalPairs.removeIf(entry -> entry.getConstraintSet().isEmpty() || entry.getPdbm().isEmpty(entry.getConstraintSet(), oracle));

        return canonicalPairs;
    }

    /**
     * 检查此 CPDBM 的语义是否为空 (即 [C, D] 是否为空)。
     *
     * @param oracle Z3 Oracle 实例。
     * @return true 如果语义为空，false 否则。
     */
    public boolean isEmpty(Z3Oracle oracle) {
        logger.info("CPDBM.isEmpty: 检查 CPDBM \n{} 是否为空。", this);
        // 检查 ConstraintSet 是否可满足
        Boolean C_satisfiable = oracle.isSatisfiable(this.constraintSet);
        if (C_satisfiable == null) { // UNKNOWN
            logger.warn("CPDBM.isEmpty: ConstraintSet {} 可满足性未知，保守返回 false。", this.constraintSet);
            return false; // 无法确定，保守返回 false
        }
        if (!C_satisfiable) {
            logger.info("CPDBM.isEmpty: ConstraintSet {} 不可满足，CPDBM 为空。", this.constraintSet);
            return true; // ConstraintSet 不可满足，则整个 CPDBM 语义为空
        }

        // 如果 ConstraintSet 可满足，则检查 PDBM 在该 ConstraintSet 下是否可满足
        // 这一步现在委托给 PDBM 自己的 isEmpty
        boolean pdbmIsEmpty = this.pdbm.isEmpty(this.constraintSet, oracle);
        logger.info("CPDBM.isEmpty: PDBM 在 ConstraintSet {} 下是否为空：{}", this.constraintSet, pdbmIsEmpty);
        return pdbmIsEmpty;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CPDBM cpdbm = (CPDBM) o;
        return constraintSet.equals(cpdbm.constraintSet) && pdbm.equals(cpdbm.pdbm);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return "CPDBM(\n  C: " + constraintSet + ",\n  D:\n" + pdbm.toString().indent(2) + ")";
    }

    @Override
    public int compareTo(CPDBM other) {
        int cmp = this.constraintSet.compareTo(other.constraintSet);
        if (cmp != 0) {
            return cmp;
        }
        return this.pdbm.compareTo(other.pdbm);
    }

    public static void main(String[] args) {
        Set<Clock> allClocks = new HashSet<>();
        Set<Parameter> allParameters = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            Clock clock = Clock.createNewClock();
            allClocks.add(clock);
        }
        Clock clock = allClocks.stream().findFirst().get();
        allClocks.add(Clock.ZERO_CLOCK);

        Z3Oracle oracle = new Z3Oracle(allParameters, allClocks);
        List<CPDBM> pdbm = PDBM.createInitial(allClocks, oracle);
        for(CPDBM c: pdbm){
            c.addGuardAndCanonical(AtomicGuard.of(clock, Clock.ZERO_CLOCK, LinearExpression.of(Rational.ONE), RelationType.LE),
                oracle
            );
        }
    }
}
