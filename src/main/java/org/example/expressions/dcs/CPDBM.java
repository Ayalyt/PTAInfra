package org.example.expressions.dcs;

import lombok.Getter;
import org.apache.commons.lang3.tuple.Pair;
import org.example.automata.base.ResetSet;
import org.example.core.Clock;
import org.example.expressions.parameters.ConstraintSet;
import org.example.symbolic.Z3Oracle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 代表受约束的参数化差分界限矩阵 (Constrained Parametric Difference-Bound Matrix, CPDBM)。
 * CPDBM 是一个对 (C, D)，其中 C 是参数约束集合，D 是 PDBM。
 * 此类是不可变的，所有操作都返回新的实例或实例集合。
 * 这是与模型检测算法交互的主要顶层API。
 */
@Getter
public final class CPDBM implements Comparable<CPDBM> {

    private static final Logger logger = LoggerFactory.getLogger(CPDBM.class);

    private final ConstraintSet constraintSet; // C
    private final PDBM pdbm;                   // D
    private final int hashCode;

    /**
     * 私有构造函数，确保通过工厂方法或操作创建实例。
     * @param constraintSet 参数约束集合。
     * @param pdbm PDBM 实例。
     */
    private CPDBM(ConstraintSet constraintSet, PDBM pdbm) {
        this.constraintSet = Objects.requireNonNull(constraintSet, "ConstraintSet cannot be null.");
        this.pdbm = Objects.requireNonNull(pdbm, "PDBM cannot be null.");
        this.hashCode = Objects.hash(constraintSet, pdbm);
        logger.debug("创建 CPDBM: (C: {})", constraintSet);
    }

    // --- 静态工厂方法 ---

    /**
     * 创建一个从无约束参数空间 (TRUE) 开始的初始符号化状态集。
     * @param allClocks 系统中所有时钟的集合。
     * @param oracle Z3 Oracle 实例。
     * @return 一个包含所有可能的、规范化的、非空的初始CPDBM的集合。
     */
    public static Set<CPDBM> createInitial(Set<Clock> allClocks, Z3Oracle oracle) {
        return createInitial(allClocks, ConstraintSet.TRUE_CONSTRAINT_SET, oracle);
    }

    /**
     * @param allClocks 系统中所有时钟的集合。
     * @param initialC 初始的参数约束集。
     * @param oracle Z3 Oracle 实例。
     * @return 一个包含所有可能的、规范化的、非空的初始CPDBM的集合。
     */
    public static Set<CPDBM> createInitial(Set<Clock> allClocks, ConstraintSet initialC, Z3Oracle oracle) {
        logger.info("创建初始 CPDBM 集合，时钟: {}, 初始约束: {}", allClocks, initialC);
        PDBM initialD = PDBM.createInitial(allClocks);
        CPDBM tempCpdbm = new CPDBM(initialC, initialD);
        return tempCpdbm.canonical(oracle);
    }

    // --- 核心操作 (返回纯粹的结果集，不进行最终过滤) ---

    /**
     * 将指定的 AtomicGuard 合取到当前 CPDBM 中。
     * 此操作可能导致 CPDBM 分裂。
     * @param newGuard 要合取的 AtomicGuard。
     * @param oracle Z3 Oracle 实例。
     * @return 包含一个或多个新 CPDBM 的集合。
     */
    public Set<CPDBM> addGuard(AtomicGuard newGuard, Z3Oracle oracle) {
        logger.debug("CPDBM.addGuard: 添加守卫 {} 到\n {}", newGuard, this);
        // 调用底层的 pdbm.addGuard，它返回 (完整的新C, 更新后的D) 对的集合
        Collection<Pair<ConstraintSet, PDBM>> pdbmResults = this.pdbm.addGuard(newGuard, this.constraintSet, oracle);

        // 将 (C, D) 对转换为 CPDBM
        return pdbmResults.stream()
                .map(pair -> new CPDBM(pair.getKey(), pair.getValue()))
                .collect(Collectors.toSet());
    }

    /**
     * 将当前 CPDBM 转换为规范形式。
     * 此操作可能导致 CPDBM 分裂。
     * @param oracle Z3 Oracle 实例。
     * @return 包含一个或多个规范化 CPDBM 的集合。
     */
    public Set<CPDBM> canonical(Z3Oracle oracle) {
        logger.debug("CPDBM.canonical: 规范化 \n{}", this);
        Collection<Pair<ConstraintSet, PDBM>> pdbmResults = this.pdbm.canonical(this.constraintSet, oracle);

        return pdbmResults.stream()
                .map(pair -> new CPDBM(pair.getKey(), pair.getValue()))
                .collect(Collectors.toSet());
    }

    /**
     * 应用时间流逝操作 (D↑) 到当前 CPDBM。
     * @return 应用时间流逝后的新 CPDBM 实例。
     */
    public CPDBM delay() {
        logger.debug("CPDBM.delay: 应用时间流逝到 {}", this);
        PDBM delayedPDBM = this.pdbm.delay();
        return new CPDBM(this.constraintSet, delayedPDBM);
    }

    /**
     * 应用时钟重置操作到当前 CPDBM。
     * @param resetSet 要重置的时钟集合及其值。
     * @return 应用重置后的新 CPDBM 实例。
     */
    public CPDBM reset(ResetSet resetSet) {
        logger.debug("CPDBM.reset: 应用重置 {} 到 \n{}", resetSet, this);
        PDBM resetPDBM = this.pdbm.reset(resetSet);
        return new CPDBM(this.constraintSet, resetPDBM);
    }

    // --- 组合操作 ---

    /**
     * @param guard 要添加的守卫。
     * @param oracle Z3 Oracle 实例。
     * @return 一个包含所有最终的、规范化的、非空的后继状态的集合。
     */
    public Set<CPDBM> addGuardAndCanonical(AtomicGuard guard, Z3Oracle oracle) {
        logger.debug("CPDBM.addGuardAndCanonical: guard={}, on\n{}", guard, this);
        Set<CPDBM> finalResults = new HashSet<>();

        // 1. 执行 addGuard，得到一个临时的后继状态集
        Set<CPDBM> afterGuard = this.addGuard(guard, oracle);

        // 2. 对 addGuard 产生的每一个后继状态执行 canonical
        for (CPDBM cpdbm : afterGuard) {
            finalResults.addAll(cpdbm.canonical(oracle));
        }

        return finalResults;
    }

    /**
     * @param oracle Z3 Oracle 实例。
     * @return 一个包含所有最终的、规范化的、非空的后继状态的集合。
     */
    public Set<CPDBM> delayAndCanonical(Z3Oracle oracle) {
        logger.debug("CPDBM.delayAndCanonical: on\n{}", this);
        return this.delay().canonical(oracle);
    }

    /**
     * @param resetSet 要重置的时钟。
     * @param oracle Z3 Oracle 实例。
     * @return 一个包含所有最终的、规范化的、非空的后继状态的集合。
     */
    public Set<CPDBM> resetAndCanonical(ResetSet resetSet, Z3Oracle oracle) {
        logger.debug("CPDBM.resetAndCanonical: reset={}, on\n{}", resetSet, this);
        // reset() 返回单个CPDBM，对其进行canonical即可
        return this.reset(resetSet).canonical(oracle);
    }


    // --- 状态查询 ---

    /**
     * 检查此 CPDBM 的语义是否为空。
     * @param oracle Z3 Oracle 实例。
     * @return true 如果语义为空，false 否则。
     */
    public boolean isEmpty(Z3Oracle oracle) {
        logger.debug("CPDBM.isEmpty: 检查 CPDBM 是否为空。C: {}", this.constraintSet);
        return this.pdbm.isEmpty(this.constraintSet, oracle);
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
        CPDBM cpdbm = (CPDBM) o;
        return constraintSet.equals(cpdbm.constraintSet) && pdbm.equals(cpdbm.pdbm);
    }

    @Override
    public int hashCode() {
        return this.hashCode;
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
}
