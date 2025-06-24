package org.example.expressions.dcs;

import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import lombok.Getter;
import org.apache.commons.lang3.tuple.Pair;
import org.example.automata.base.ResetSet;
import org.example.core.Clock;
import org.example.expressions.RelationType;
import org.example.expressions.ToZ3BoolExpr;
import org.example.expressions.parameters.LinearExpression;
import org.example.expressions.parameters.ParameterConstraint;
import org.example.expressions.parameters.ConstraintSet;
import org.example.symbolic.Z3Oracle;
import org.example.symbolic.Z3VariableManager;
import org.example.utils.Rational;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 代表参数化差分界限矩阵 (Parametric Difference-Bound Matrix, PDBM)。
 * PDBM 是时钟差分约束的规范化合取表示，其边界是含参线性表达式。
 * 此类是不可变的。
 * PDBM 会返回多个 (ConstraintSet, PDBM) 对。
 */
@Getter
public final class PDBM implements Comparable<PDBM>, ToZ3BoolExpr {

    private static final Logger logger = LoggerFactory.getLogger(PDBM.class);

    /** 按索引顺序存储时钟列表, clockList.get(0)是零时钟，由构造函数保证 */
    private final List<Clock> clockList;

    /** 将时钟映射到其在矩阵中的索引 (行/列). */
    private final Map<Clock, Integer> clockIndexMap;

    /** 矩阵大小 (时钟数量) */
    private final int size;

    /**
     * 边界矩阵. boundsMatrix[i][j]存储 c_i - c_j 的 AtomicGuard。
     */
    private final AtomicGuard[][] boundsMatrix;

    private final int hashCode;

    // --- 构造函数 ---

    /**
     * 私有构造函数。
     *
     * @param clockList     按索引排序的时钟列表。
     * @param clockIndexMap 时钟到索引的映射。
     * @param boundsMatrix  边界矩阵 (将被深拷贝)。
     */
    private PDBM(List<Clock> clockList, Map<Clock, Integer> clockIndexMap, AtomicGuard[][] boundsMatrix) {
        // 确保零时钟在列表的第一个位置
        List<Clock> finalClockList = new ArrayList<>(clockList);
        if (!finalClockList.contains(Clock.ZERO_CLOCK)) {
            finalClockList.add(0, Clock.ZERO_CLOCK); // 确保 x0 在索引 0
        } else if (finalClockList.indexOf(Clock.ZERO_CLOCK) != 0) {
            // 如果 x0 不在索引 0，则将其移到索引 0，并重新排序其他时钟
            finalClockList.remove(Clock.ZERO_CLOCK);
            finalClockList.add(0, Clock.ZERO_CLOCK);
        }
        // 重新排序非零时钟，确保一致性
        finalClockList.sort(Comparator.comparingInt(Clock::getId));
        // 确保 x0 仍然在第一个位置
        if (finalClockList.get(0) != Clock.ZERO_CLOCK) {
            finalClockList.remove(Clock.ZERO_CLOCK);
            finalClockList.add(0, Clock.ZERO_CLOCK);
        }

        this.clockList = Collections.unmodifiableList(finalClockList);

        Map<Clock, Integer> tempIndexMap = new HashMap<>();
        for (int i = 0; i < this.clockList.size(); i++) {
            tempIndexMap.put(this.clockList.get(i), i);
        }
        this.clockIndexMap = Collections.unmodifiableMap(tempIndexMap);
        this.size = this.clockList.size();

        this.boundsMatrix = new AtomicGuard[size][size];
        // 深拷贝矩阵内容
        for (int i = 0; i < size; i++) {
            // AtomicGuard 是不可变的，所以浅拷贝引用即可
            System.arraycopy(boundsMatrix[i], 0, this.boundsMatrix[i], 0, size);
        }
        this.hashCode = Objects.hash(this.clockList, Arrays.deepHashCode(this.boundsMatrix));
        logger.debug("创建 PDBM: {}", this);
    }

    /**
     * 创建一个表示所有时钟非负区域 (ci >= 0) 的初始 PDBM。
     *
     * @param allClocks PTA 中所有时钟的集合 (应包含零时钟 x0)。
     * @return 代表初始非负区域 (ci >= 0 for all i) 的 PDBM。
     */
    public static PDBM createInitial(Set<Clock> allClocks) {
        List<Clock> clockList = new ArrayList<>(allClocks);
        if (!clockList.contains(Clock.ZERO_CLOCK)) {
            clockList.add(0, Clock.ZERO_CLOCK);
        }
        List<Clock> sortedNonZeroClocks = clockList.stream()
                .sorted(Comparator.comparingInt(Clock::getId))
                .toList();

        List<Clock> finalClockList = new ArrayList<>();
        finalClockList.add(Clock.ZERO_CLOCK);
        finalClockList.addAll(sortedNonZeroClocks);

        Map<Clock, Integer> clockIndexMap = new HashMap<>();
        for (int i = 0; i < finalClockList.size(); i++) {
            clockIndexMap.put(finalClockList.get(i), i);
        }

        int size = finalClockList.size();
        AtomicGuard[][] initialBounds = new AtomicGuard[size][size];

        // 初始化矩阵
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                Clock ci = finalClockList.get(i);
                Clock cj = finalClockList.get(j);

                if (i == j) {
                    // 对角线元素：ci - ci <= 0 (恒真)
                    initialBounds[i][j] = AtomicGuard.of(ci, cj, LinearExpression.of(Rational.ZERO), RelationType.LE);
                } else if (j == clockIndexMap.get(Clock.ZERO_CLOCK)) { // 处理第 0 列 (ci - x0)
                    // ci - x0 >= 0 (ci >= 0) 等价于 x0 - ci <= 0
                    initialBounds[i][j] = AtomicGuard.of(Clock.ZERO_CLOCK, ci, LinearExpression.of(Rational.ZERO), RelationType.LE);
                } else {
                    // 其他元素：ci - cj <= infinity (无上界)
                    initialBounds[i][j] = AtomicGuard.of(ci, cj, LinearExpression.of(Rational.INFINITY), RelationType.LE); // 使用 LE 和 INFINITY 表示无上界
                }
            }
        }
        return new PDBM(finalClockList, clockIndexMap, initialBounds);
    }

    /**
     * 获取指定索引位置的 AtomicGuard。
     * @param i 行索引。
     * @param j 列索引。
     * @return AtomicGuard。
     */
    public AtomicGuard getGuard(int i, int j) {
        if (i < 0 || i >= size || j < 0 || j >= size) {
            throw new IndexOutOfBoundsException("Index (" + i + ", " + j + ")越界：" + size);
        }
        return this.boundsMatrix[i][j];
    }

    // === PDBM 核心操作 ===

    /**
     * 将指定的 AtomicGuard 合取到当前 PDBM 中。
     * 此操作可能导致参数空间分裂，因此返回一个 (ConstraintSet, PDBM) 对的列表。
     *
     * @param newGuard 要合取的 AtomicGuard。
     * @param currentConstraintSet 当前的参数约束集 (C)，用于 Z3 Oracle 查询。
     * @param oracle Z3 Oracle 实例。
     * @return 包含多个 (ConstraintSet, PDBM) 对的列表。如果导致不可满足，返回空列表。
     */
    public List<Map.Entry<ConstraintSet, PDBM>> addGuard(AtomicGuard newGuard, ConstraintSet currentConstraintSet, Z3Oracle oracle) {
        logger.debug("PDBM.addGuard: 尝试添加新约束 {} 到 PDBM {} under C {}", newGuard, this, currentConstraintSet);
        List<Map.Entry<ConstraintSet, PDBM>> resultPairs = new ArrayList<>();


        // 1. 时钟合法性检查
        if (!clockList.contains(newGuard.getClock1()) && !clockList.contains(newGuard.getClock2())) {
            logger.warn("PDBM.addGuard: 约束 {} 中的时钟未在 PDBM 中找到，忽略此约束。", newGuard);
            // 如果时钟不在 PDBM 中，则此守卫不影响当前 PDBM，返回原始 (C, D) 对
            resultPairs.add(Map.entry(currentConstraintSet, this));
            return resultPairs;
        }

        Integer i, j;
        Pair<Integer, Integer>  positioning = positioning(newGuard);
        i = positioning.getLeft();
        j = positioning.getRight();

        // 2. 构造 C(D, f) 约束：e_ij (rel_ij AND rel_new) e_new
        // 转换为 ParameterConstraint 形式：(e_ij - e_new) (rel_ij AND rel_new) 0
        ParameterConstraint comparisonConstraint = createComparisonConstraint(newGuard, i, j);

        // 3. Oracle 检查覆盖关系
        Z3Oracle.OracleResult oracleResult = oracle.checkCoverage(comparisonConstraint, currentConstraintSet);
        logger.debug("PDBM.addGuard: Oracle 结果 for C(D,f) {} on C {} is {}", comparisonConstraint, currentConstraintSet, oracleResult);

        // 4. 根据 Oracle 结果处理
        switch (oracleResult) {
            case YES:
                // Rule R1: C |= C(D, f) -> (C, D)
                // 新守卫已被当前 PDBM 隐含，无需修改 PDBM
                resultPairs.add(Map.entry(currentConstraintSet, this));
                break;
            case NO:
                // Rule R2: C |= ¬C(D, f) -> (C, D[f])
                // 新守卫更严格，更新 PDBM 矩阵
                AtomicGuard[][] newBoundsMatrixForNo = deepCopyBoundsMatrix();
                newBoundsMatrixForNo[i][j] = newGuard; // 更新为更严格的守卫
                PDBM newPDBMForNo = new PDBM(this.clockList, this.clockIndexMap, newBoundsMatrixForNo);
                resultPairs.add(Map.entry(currentConstraintSet, newPDBMForNo));
                break;
            case SPLIT:
                // Rule R3: (C ∪ {C(D, f)}, D)
                // Rule R4: (C ∪ {¬C(D, f)}, D[f])
                // PDBM 分裂成两个 (ConstraintSet, PDBM) 对
                // 分支 1: 约束集添加 C(D, f)，PDBM 保持不变
                ConstraintSet C1 = currentConstraintSet.and(comparisonConstraint);
                resultPairs.add(Map.entry(C1, this));

                // 分支 2: 约束集添加 ¬C(D, f)，PDBM 矩阵更新为新守卫
                ConstraintSet C2 = currentConstraintSet.and(comparisonConstraint.negate());
                AtomicGuard[][] newBoundsMatrixForSplit = deepCopyBoundsMatrix();
                newBoundsMatrixForSplit[i][j] = newGuard; // 更新为更严格的守卫
                PDBM newPDBMForSplit = new PDBM(this.clockList, this.clockIndexMap, newBoundsMatrixForSplit);
                resultPairs.add(Map.entry(C2, newPDBMForSplit));
                break;
            case UNKNOWN:
                logger.warn("PDBM.addGuard: Z3 Oracle 返回 UNKNOWN 状态，无法确定约束关系。返回空列表。");
                break;
            default:
        }
        logger.debug("PDBM.addGuard: 返回 {} 个 (ConstraintSet, PDBM) 对。", resultPairs.size());
        return resultPairs;
    }

    /**
     * 根据newGuard的符号方向确认旧约束在上三角或下三角
     * @param newGuard
     * @return
     */
    private Pair<Integer, Integer> positioning (AtomicGuard newGuard){
        Integer i = 0;
        Integer j = 0;
        boolean isGreater = newGuard.getRelation().isGreater();
        if (isGreater){
            i = clockIndexMap.get(newGuard.getClock2());
            j = clockIndexMap.get(newGuard.getClock1());
        } else {
            i = clockIndexMap.get(newGuard.getClock1());
            j = clockIndexMap.get(newGuard.getClock2());
        }
        return Pair.of(i, j);
    }

    /**
     * 辅助方法：根据两个 AtomicGuard 构造一个 ParameterConstraint 用于比较。
     * 论文中 C(D, f) = e_ij (<ij ⇒ <) e
     * 这里的 AtomicGuard 实例都应是规范化的上界形式 (LT 或 LE)。
     *
     * @param newGuard 要添加的 AtomicGuard (c_i - c_j ~ E_new)
     * @return 比较这两个 AtomicGuard 边界的 ParameterConstraint。
     */
    private ParameterConstraint createComparisonConstraint(AtomicGuard newGuard, Integer i, Integer j) {
        AtomicGuard currentGuard = boundsMatrix[i][j];

        RelationType finalRelation;
        if (newGuard.getRelation() == RelationType.LT || currentGuard.getRelation() == RelationType.LT) {
            finalRelation = RelationType.LT;
        } else if (newGuard.getRelation() == RelationType.GT || currentGuard.getRelation() == RelationType.GT) {
            finalRelation = RelationType.GT;
        } else if (i <= j) {
            finalRelation = RelationType.LE;
        } else {
            finalRelation = RelationType.GE;
        }

        // 构造 ParameterConstraint: (currentBound - newBound) finalRelation 0
        // TODO: 检查一下bound构造方向对于后续的影响如何
        return ParameterConstraint.of(
                i <= j ? currentGuard.getBound().subtract(newGuard.getBound()) : newGuard.getBound().subtract(currentGuard.getBound()),
                LinearExpression.of(Rational.ZERO),
                finalRelation
        );
    }

    /**
     * 将 PDBM 转换为规范形式 (Canonical Form)。
     * 使用符号 Floyd-Warshall 算法计算所有点对之间的最短路径，收紧约束。
     * 规范形式对于判空和包含检查是必需的。
     * 此操作可能导致参数空间分裂，因此返回一个 (ConstraintSet, PDBM) 对的列表。
     *
     * @param initialConstraintSet 初始的参数约束集 (C)，用于 Z3 Oracle 查询。
     * @param oracle Z3 Oracle 实例。
     * @return 包含一个或多个规范化 (ConstraintSet, PDBM) 对的列表。如果导致不可满足，返回空列表。
     */
    public List<Map.Entry<ConstraintSet, PDBM>> canonical(ConstraintSet initialConstraintSet, Z3Oracle oracle) {
        logger.debug("PDBM.canonical: 规范化 PDBM {} under initial C {}", this, initialConstraintSet);

        // 维护一个待处理的 (ConstraintSet, PDBM) 对的队列
        Queue<Map.Entry<ConstraintSet, PDBM>> queue = new LinkedList<>();
        queue.add(Map.entry(initialConstraintSet, this));

        // 维护一个已处理的规范化 (ConstraintSet, PDBM) 对的集合
        Set<Map.Entry<ConstraintSet, PDBM>> canonicalResults = new HashSet<>();

        // 迭代直到队列为空
        while (!queue.isEmpty()) {
            Map.Entry<ConstraintSet, PDBM> currentEntry = queue.poll();
            ConstraintSet currentC = currentEntry.getKey();
            PDBM currentD = currentEntry.getValue();

            // 检查当前 (C, D) 是否已经处理过或语义为空
            if (currentC.isEmpty() || currentD.isEmpty(currentC, oracle)) { // isEmpty 检查 C 是否矛盾，以及 D 在 C 下是否可满足
                logger.debug("PDBM.canonical: 跳过空或已处理的 (C: {}, D: {})", currentC, currentD);
                continue;
            }
            if (canonicalResults.contains(currentEntry)) {
                logger.debug("PDBM.canonical: 跳过重复的 (C: {}, D: {})", currentC, currentD);
                continue;
            }

            // 尝试进行一步 Floyd-Warshall 更新
            boolean updatedInThisIteration = false;
            AtomicGuard[][] tempBoundsMatrix = currentD.deepCopyBoundsMatrix(); // 当前迭代的临时矩阵
            Z3Oracle.OracleResult oracleResult = Z3Oracle.OracleResult.UNKNOWN; // 初始化为 UNKNOWN
            // 遍历 k, i, j
            for (int k = 0; k < size; k++) {
                for (int i = 0; i < size; i++) {
                    for (int j = 0; j < size; j++) {
                        // 跳过对角线元素
                        if (i == j) {
                            continue;
                        }

                        AtomicGuard Dik = tempBoundsMatrix[i][k];
                        AtomicGuard Dkj = tempBoundsMatrix[k][j];
                        AtomicGuard Dij = tempBoundsMatrix[i][j];

                        // 计算通过 k 的新路径的边界和关系
                        LinearExpression potentialNewBoundExpr = Dik.getBound().add(Dkj.getBound());
                        RelationType potentialNewRelation = Dik.getRelation().and(Dkj.getRelation());

                        // 构造潜在的新守卫
                        AtomicGuard potentialNewGuard = AtomicGuard.of(
                                clockList.get(i), clockList.get(j),
                                potentialNewBoundExpr, potentialNewRelation
                        );

                        // 比较当前边界 Dij 和潜在的新边界 potentialNewGuard
                        // C(D, f) = Dij (Dij.relation AND potentialNewGuard.relation) potentialNewGuard
                        ParameterConstraint comparisonConstraint = ParameterConstraint.of(
                                Dij.getBound(),
                                potentialNewGuard.getBound(),
                                Dij.getRelation().and(potentialNewGuard.getRelation())
                        );

                        oracleResult = oracle.checkCoverage(comparisonConstraint, currentC);

                        switch (oracleResult) {
                            case YES:
                                // Dij 已经比 potentialNewGuard 更严格或相等，无需更新
                                break;
                            case NO:
                                // potentialNewGuard 更严格，更新 Dij
                                tempBoundsMatrix[i][j] = potentialNewGuard;
                                updatedInThisIteration = true;
                                logger.debug("PDBM.canonical: 更新边界 {} - {} 为 {}", clockList.get(i), clockList.get(j), potentialNewGuard);
                                break;
                            case SPLIT:
                                // 发生分裂，将新的 (C, D) 对加入队列
                                // 分支 1: 约束集添加 C(D, f)，PDBM 保持不变 (但需要复制当前状态)
                                ConstraintSet C_split1 = currentC.and(comparisonConstraint);
                                PDBM D_split1 = new PDBM(currentD.clockList, currentD.clockIndexMap, currentD.boundsMatrix); // 复制当前 PDBM
                                queue.add(Map.entry(C_split1, D_split1));
                                logger.debug("PDBM.canonical: 发生分裂，添加分支 1: (C: {}, D: {})", C_split1, D_split1);

                                // 分支 2: 约束集添加 ¬C(D, f)，PDBM 矩阵更新为新守卫
                                ConstraintSet C_split2 = currentC.and(comparisonConstraint.negate());
                                AtomicGuard[][] D_split2_matrix = currentD.deepCopyBoundsMatrix();
                                D_split2_matrix[i][j] = potentialNewGuard;
                                PDBM D_split2 = new PDBM(currentD.clockList, currentD.clockIndexMap, D_split2_matrix);
                                queue.add(Map.entry(C_split2, D_split2));
                                logger.debug("PDBM.canonical: 发生分裂，添加分支 2: (C: {}, D: {})", C_split2, D_split2);

                                // 由于发生了分裂，当前路径已经分叉，可以跳出内层循环，处理下一个队列元素
                                // 或者更严格地，直接将当前 (C,D) 标记为已处理，并依赖队列中的新分支
                                // 这里我们选择将当前 (C,D) 视为已处理，并依赖新分支
                                updatedInThisIteration = true; // 标记为已更新，以便重新处理
                                break; // 跳出 j 循环，重新开始 k, i, j 循环，因为矩阵可能已分裂
                            case UNKNOWN:
                                logger.warn("PDBM.canonical: Z3 Oracle 返回 UNKNOWN 状态，无法确定边界关系。此 (C, D) 对被视为不可达或无效。");
                                // 标记为已更新，以便当前 (C,D) 不被添加到最终结果，并跳出循环
                                updatedInThisIteration = true;
                                break;
                            default:
                                logger.error("PDBM.canonical: 未知的 Oracle 结果: {}", oracleResult);
                                throw new IllegalStateException("未知的 Oracle 结果: " + oracleResult);
                        }
                        if (updatedInThisIteration && oracleResult == Z3Oracle.OracleResult.SPLIT) {
                            // 如果发生了分裂，当前 PDBM 已经分叉，需要重新从队列中取新的分支进行处理
                            // 此时，当前 PDBM 已经不再是单一的演化路径，所以直接跳出所有循环，让外层 while 循环处理新加入的队列元素
                            // 这种处理方式确保了每次迭代都从一个“稳定”的 (C,D) 开始，并在分裂时立即停止当前路径的探索
                            break; // 跳出 j 循环
                        }
                    }
                    if (updatedInThisIteration && oracleResult == Z3Oracle.OracleResult.SPLIT) {
                        break; // 跳出 i 循环
                    }
                }
                if (updatedInThisIteration && oracleResult == Z3Oracle.OracleResult.SPLIT) {
                    break; // 跳出 k 循环
                }
            }

            // 如果在当前迭代中发生了更新（包括分裂），则将更新后的 PDBM 重新加入队列进行下一轮规范化
            // 如果没有更新，则表示当前 PDBM 在当前 ConstraintSet 下已达到规范形式
            if (updatedInThisIteration) {
                // 如果发生了分裂，新的分支已经加入队列，当前 PDBM 不再需要加入
                if (oracleResult != Z3Oracle.OracleResult.SPLIT && oracleResult != Z3Oracle.OracleResult.UNKNOWN) {
                    queue.add(Map.entry(currentC, new PDBM(this.clockList, this.clockIndexMap, tempBoundsMatrix)));
                }
            } else {
                // 没有更新，表示已达到规范形式，加入结果集
                canonicalResults.add(currentEntry);
            }
        }
        logger.debug("PDBM.canonical: 规范化完成，返回 {} 个 (ConstraintSet, PDBM) 对。", canonicalResults.size());
        return new ArrayList<>(canonicalResults);
    }


    /**
     * 移除相对于零时钟的上界约束，将 M[i][0] (代表 c_i - x0 <= V, 即 c_i <= V) 设置为无穷大 (< ∞)。
     * 此操作返回一个新的 PDBM 实例。
     *
     * @return 移除约束后的新 PDBM 实例。
     */
    public PDBM delay() {
        logger.debug("PDBM.delay: 应用时间流逝操作到 PDBM {}", this);
        AtomicGuard[][] newBoundsMatrix = deepCopyBoundsMatrix();
        int zeroClockIndex = clockIndexMap.get(Clock.ZERO_CLOCK);

        for (int i = 0; i < size; i++) {
            // 对于所有非零时钟 ci (i != 0)，设置 ci - x0 < ∞
            // 对应矩阵元素 boundsMatrix[i][0]
            if (i != zeroClockIndex) {
                Clock ci = clockList.get(i);
                newBoundsMatrix[i][zeroClockIndex] = AtomicGuard.of(
                        ci,
                        Clock.ZERO_CLOCK,
                        LinearExpression.of(Rational.INFINITY),
                        RelationType.LT
                );
            }
        }
        logger.debug("PDBM.delay: 时间流逝操作完成。");
        return new PDBM(this.clockList, this.clockIndexMap, newBoundsMatrix);
    }

    /**
     * 将指定的时钟重置为指定值。
     * 此操作返回一个新的 PDBM 实例。
     *
     * @param resetSet 要重置的时钟集合及其值。
     * @return 重置后的新 PDBM 实例。
     */
    public PDBM reset(ResetSet resetSet) {
        logger.debug("PDBM.reset: 应用重置操作 {} 到 PDBM {}", resetSet, this);
        AtomicGuard[][] newBoundsMatrix = deepCopyBoundsMatrix();
        int zeroClockIndex = clockIndexMap.get(Clock.ZERO_CLOCK);

        for (Map.Entry<Clock, Rational> entry : resetSet.getResets().entrySet()) {
            Clock resetClock = entry.getKey();
            Rational resetValue = entry.getValue();
            int resetClockIndex = clockIndexMap.get(resetClock);

            // 1. 处理对角线元素：xi - xi <= 0
            newBoundsMatrix[resetClockIndex][resetClockIndex] = AtomicGuard.of(
                    resetClock, resetClock, LinearExpression.of(Rational.ZERO), RelationType.LE
            );

            // 2. 更新所有 x_resetClock - x_j 的边界 (即 resetClockIndex 行)
            for (int j = 0; j < size; j++) {
                if (j == resetClockIndex) {
                    continue; // 跳过对角线
                }

                Clock cj = clockList.get(j);
                // 论文中 D_kj (k行j列) for j ≠ i, by (e0j + b, <0j)
                // 这里的 k 是重置时钟 i_reset
                // D_i_reset,j = (e_0j + b, <_0j)
                AtomicGuard x0_minus_cj_guard = newBoundsMatrix[zeroClockIndex][j]; // x0 - cj
                LinearExpression newBound_i_j = LinearExpression.of(resetValue).add(x0_minus_cj_guard.getBound());
                RelationType newRelation_i_j = x0_minus_cj_guard.getRelation();
                newBoundsMatrix[resetClockIndex][j] = AtomicGuard.of(resetClock, cj, newBound_i_j, newRelation_i_j);

                // 3. 更新所有 x_j - x_resetClock 的边界 (即 j 列)
                // 论文中 D_jk (j行k列) for j ≠ i, by (ej0 - b, <j0)
                // 这里的 k 是重置时钟 i_reset
                // D_j,i_reset = (e_j0 - b, <_j0)
                AtomicGuard cj_minus_x0_guard = newBoundsMatrix[j][zeroClockIndex]; // cj - x0
                LinearExpression newBound_j_i = cj_minus_x0_guard.getBound().subtract(LinearExpression.of(resetValue));
                RelationType newRelation_j_i = cj_minus_x0_guard.getRelation();
                newBoundsMatrix[j][resetClockIndex] = AtomicGuard.of(cj, resetClock, newBound_j_i, newRelation_j_i);
            }
        }
        logger.debug("PDBM.reset: 重置操作完成。");
        return new PDBM(this.clockList, this.clockIndexMap, newBoundsMatrix);
    }

    /**
     * 检查此 PDBM 的语义是否为空 (即 [D]v 是否为空)。
     * 这需要将 PDBM 转换为 Z3 表达式并与 ConstraintSet 合取后查询。
     *
     * @param currentConstraintSet 当前的参数约束集 (C)，用于 Z3 Oracle 查询。
     * @param oracle Z3 Oracle 实例。
     * @return true 如果语义为空，false 否则。
     */
    public boolean isEmpty(ConstraintSet currentConstraintSet, Z3Oracle oracle) {
        logger.debug("PDBM.isEmpty: 检查 PDBM {} 在 ConstraintSet {} 下是否为空。", this, currentConstraintSet);
        // 将 PDBM 和 ConstraintSet 的 Z3 表达式合取后查询
        BoolExpr combinedExpr = oracle.getContext().mkAnd(
                currentConstraintSet.toZ3BoolExpr(oracle.getContext(), oracle.getVarManager()),
                this.toZ3BoolExpr(oracle.getContext(), oracle.getVarManager())
        );
        com.microsoft.z3.Status status = oracle.check(combinedExpr);
        boolean result = (status == com.microsoft.z3.Status.UNSATISFIABLE);
        logger.debug("PDBM.isEmpty: 检查结果为 {} (Status: {})", result, status);
        return result;
    }

    /**
     * 深拷贝内部的 boundsMatrix。
     * @return 新的 AtomicGuard[][] 矩阵。
     */
    private AtomicGuard[][] deepCopyBoundsMatrix() {
        AtomicGuard[][] newMatrix = new AtomicGuard[size][size];
        for (int i = 0; i < size; i++) {
            System.arraycopy(this.boundsMatrix[i], 0, newMatrix[i], 0, size);
        }
        return newMatrix;
    }

    // === Z3 转换 ===

    @Override
    public BoolExpr toZ3BoolExpr(Context ctx, Z3VariableManager varManager) {
        List<BoolExpr> z3Guards = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                z3Guards.add(boundsMatrix[i][j].toZ3BoolExpr(ctx, varManager));
            }
        }
        if (z3Guards.isEmpty()) {
            return ctx.mkTrue(); // 空 PDBM 表示恒真
        }
        return ctx.mkAnd(z3Guards.toArray(new BoolExpr[0]));
    }

    // === Object 方法 ===

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PDBM pdbm = (PDBM) o;
        if (!clockList.equals(pdbm.clockList)) {
            return false;
        }
        return Arrays.deepEquals(boundsMatrix, pdbm.boundsMatrix);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        int maxClockNameWidth = 0;
        for (Clock clock : clockList) {
            maxClockNameWidth = Math.max(maxClockNameWidth, clock.getName().length());
        }
        int elementWidth = 20; // 适应 LinearExpression

        // 列标题 时钟名
        sb.append(String.format("%" + maxClockNameWidth + "s |", ""));
        for (Clock clock : clockList) {
            sb.append(String.format(" %-" + elementWidth + "s", clock.getName()));
        }
        sb.append("\n");

        // 分隔线
        sb.append("-".repeat(maxClockNameWidth + 1));
        sb.append("+");
        sb.append("-".repeat((elementWidth + 1) * size));
        sb.append("\n");

        // 行
        for (int i = 0; i < size; i++) {
            Clock clock_i = this.clockList.get(i);
            sb.append(String.format("%" + maxClockNameWidth + "s |", clock_i.getName())); // 行标题 时钟名
            for (int j = 0; j < size; j++) {
                AtomicGuard guard = boundsMatrix[i][j];
                String element = String.format("%s %s", guard.getRelation().getSymbol(), guard.getBound().toString());
                sb.append(String.format(" %-" + elementWidth + "s", element));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    @Override
    public int compareTo(PDBM other) {
        // 比较时钟列表
        int cmp = Integer.compare(this.size, other.size);
        if (cmp != 0) {
            return cmp;
        }
        // 比较 clockList 的内容，确保顺序一致
        for (int i = 0; i < size; i++) {
            cmp = this.clockList.get(i).compareTo(other.clockList.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }

        // 比较矩阵内容
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                cmp = this.boundsMatrix[i][j].compareTo(other.boundsMatrix[i][j]);
                if (cmp != 0) {
                    return cmp;
                }
            }
        }
        return 0;
    }
}
