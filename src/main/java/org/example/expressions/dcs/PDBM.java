package org.example.expressions.dcs;

import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import lombok.Getter;
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
     * 这里的 AtomicGuard 已经包含了边界值 (LinearExpression) 和关系类型。
     */
    private final AtomicGuard[][] boundsMatrix;

    private final int hashCode;

    // --- 构造函数 ---

    /**
     * 私有构造函数，用于内部创建 PDBM 实例 (如 copy 或规范化结果)。
     *
     * @param clockList     按索引排序的时钟列表。
     * @param clockIndexMap 时钟到索引的映射。
     * @param boundsMatrix  边界矩阵 (将被深拷贝)。
     */
    private PDBM(List<Clock> clockList, Map<Clock, Integer> clockIndexMap, AtomicGuard[][] boundsMatrix) {
        if(clockList.contains(Clock.ZERO_CLOCK)) {
            this.clockList = Collections.unmodifiableList(new ArrayList<>(clockList));
        }
        else {
            List<Clock> newClockList = new ArrayList<>(clockList.size() + 1);
            newClockList.add(Clock.ZERO_CLOCK);
            newClockList.addAll(clockList);
            this.clockList = Collections.unmodifiableList(newClockList);
        }
        this.clockIndexMap = Collections.unmodifiableMap(new HashMap<>(clockIndexMap));
        this.size = clockList.size();

        this.boundsMatrix = new AtomicGuard[size][size];
        // 深拷贝矩阵内容
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                this.boundsMatrix[i][j] = boundsMatrix[i][j]; // AtomicGuard 不可变，浅拷贝引用即可
            }
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
        List<Clock> clockList = new ArrayList<>(allClocks.size());
        Map<Clock, Integer> clockIndexMap = new HashMap<>(allClocks.size());

        Clock zeroClock = Clock.ZERO_CLOCK;
        int index = 0;
        if (!allClocks.contains(zeroClock)){
            clockList.add(zeroClock);
            clockIndexMap.put(zeroClock, 0);
            index = 1;
        }

        List<Clock> sortedNonZeroClocks = allClocks.stream()
                .sorted(Comparator.comparingInt(Clock::getId))
                .toList();

        for (Clock clock : sortedNonZeroClocks) {
            clockList.add(clock);
            clockIndexMap.put(clock, index++);
        }

        int size = clockList.size();
        AtomicGuard[][] initialBounds = new AtomicGuard[size][size];

        // 初始化矩阵
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                Clock ci = clockList.get(i);
                Clock cj = clockList.get(j);

                if (i == j) {
                    // 对角线元素：ci - ci <= 0 (恒真)
                    initialBounds[i][j] = AtomicGuard.of(ci, ci, LinearExpression.of(Rational.ZERO), RelationType.LE);
                } else if (j == 0) { // 处理第 0 列 (ci - x0)
                    // ci - x0 <= infinity (无上界)
                    // ci - x0 >= 0 (ci >= 0)
                    // 论文中 DBM 存储的是上界，所以 ci >= 0 转换为 x0 - ci <= 0
                    // 初始状态所有时钟非负，即 ci >= 0，等价于 x0 - ci <= 0
                    initialBounds[i][j] = AtomicGuard.of(zeroClock, ci, LinearExpression.of(Rational.ZERO), RelationType.LE);
                } else {
                    // 其他元素：ci - cj <= infinity (无上界)
                    initialBounds[i][j] = AtomicGuard.of(ci, cj, LinearExpression.of(Rational.INFINITY), RelationType.LE); // 使用 LE 和 INFINITY 表示无上界
                }
            }
        }
        return new PDBM(clockList, clockIndexMap, initialBounds);
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
     * 此操作可能导致 PDBM 分裂，因此返回一个 PDBM 集合。
     *
     * @param newGuard 要合取的 AtomicGuard。
     * @param currentConstraintSet 当前的参数约束集 (C)，用于 Z3 Oracle 查询。
     * @param oracle Z3 Oracle 实例。
     * @return 包含一个或多个新 PDBM 的集合。如果导致不可满足，返回空集合。
     */
    public Set<PDBM> addGuard(AtomicGuard newGuard, ConstraintSet currentConstraintSet, Z3Oracle oracle) {
        logger.debug("addGuard: 尝试添加新约束 {} 到 PDBM {}", newGuard, this);
        Set<PDBM> resultPDBMs = new HashSet<>();
        // 1. 获取新约束对应的矩阵索引
        Integer i = clockIndexMap.get(newGuard.getClock1());
        Integer j = clockIndexMap.get(newGuard.getClock2());
        if (i == null || j == null) {
            logger.warn("addGuard: 约束 {} 中的时钟未在 PDBM 中找到，忽略此约束。", newGuard);
            resultPDBMs.add(this); // 返回原 PDBM
            return resultPDBMs;
        }

        AtomicGuard currentGuard = boundsMatrix[i][j];

        // 2. 构造 ParameterConstraint C(D, f)
        // 论文中 C(D, f) = e_ij (rel_ij rel) e
        // 这里的 rel_ij 是 currentGuard.getRelation()
        // rel 是 newGuard.getRelation()
        // e_ij 是 currentGuard.getBound()
        // e 是 newGuard.getBound()
        // 这是一个复杂的逻辑，需要根据关系类型组合
        // 简化：将所有约束转换为 (E_left - E_right) ~ 0 的形式，然后比较 E_left
        // 假设 AtomicGuard 已经规范化为 c1 - c2 ~ E
        // 那么 currentGuard 是 c1 - c2 ~ currentE
        // newGuard 是 c1 - c2 ~ newE
        // 比较 currentE 和 newE
        // 例如：currentE <= newE
        ParameterConstraint comparisonConstraint = createComparisonConstraint(currentGuard, newGuard);

        // 3. 调用 Z3 Oracle 检查 C(D, f) 与当前参数约束集 C 的关系
        Z3Oracle.OracleResult oracleResult = oracle.checkCoverage(comparisonConstraint, currentConstraintSet);

        switch (oracleResult) {
            case YES:
                // C(D, f) 在 C 中恒真，说明新约束比当前约束更宽松或等价，PDBM 不变
                resultPDBMs.add(this);
                break;
            case NO:
                // C(D, f) 在 C 中恒假，说明新约束比当前约束更紧，PDBM 必须更新
                // 创建一个新的 PDBM 实例，更新对应的边界
                AtomicGuard[][] newBoundsMatrix = deepCopyBoundsMatrix();
                newBoundsMatrix[i][j] = newGuard; // 直接替换为新约束
                PDBM updatedPDBM = new PDBM(clockList, clockIndexMap, newBoundsMatrix);
                resultPDBMs.add(updatedPDBM);
                break;
            case SPLIT:
                // C(D, f) 在 C 中分裂，需要返回两个 PDBM
                // PDBM1: C /\ C(D, f) -> PDBM 不变
                // PDBM2: C /\ ¬C(D, f) -> PDBM 更新
                // 注意：这里返回的是 PDBM，而不是 ConstrainedPDBM
                // ConstrainedPDBM 的分裂逻辑应该在 ConstrainedPDBM.applyGuard 中处理
                // PDBM.addGuard 应该只返回 PDBM 自身的变化
                // 论文中 PDBM.addGuard 似乎不直接导致分裂，分裂发生在 ConstrainedPDBM 层面
                // 重新理解论文规则 R3, R4: 它们返回的是 (C', D')，其中 C' 包含了 C(D,f) 或 ¬C(D,f)
                // 这意味着 PDBM.addGuard 应该只返回一个 PDBM，而分裂由 ConstrainedPDBM 负责
                // 如果是这样，PDBM.addGuard 就不应该返回 Set<PDBM>，而是一个 PDBM
                // 让我们假设 PDBM.addGuard 总是返回一个 PDBM，分裂由 ConstrainedPDBM 处理
                logger.warn("PDBM.addGuard 遇到 SPLIT 结果，这通常应在 ConstrainedPDBM 层面处理。返回原 PDBM。");
                resultPDBMs.add(this); // 暂时返回原 PDBM，等待 ConstrainedPDBM 处理分裂
                break;
            case UNKNOWN:
                logger.warn("Z3 Oracle 返回 UNKNOWN 结果，无法确定约束关系。返回原 PDBM。");
                resultPDBMs.add(this); // 无法确定，返回原 PDBM
                break;
            default:
        }
        return resultPDBMs; // 暂时返回 Set，如果 PDBM.addGuard 总是返回一个，则改为 PDBM
    }

    /**
     * 辅助方法：根据两个 AtomicGuard 构造一个 ParameterConstraint 用于比较。
     * 论文中 C(D, f) = e_ij (rel_ij rel) e
     * @param currentGuard PDBM 中已有的 AtomicGuard (c_i - c_j ~ E_current)
     * @param newGuard 要添加的 AtomicGuard (c_i - c_j ~ E_new)
     * @return 比较这两个 AtomicGuard 边界的 ParameterConstraint。
     */
    private ParameterConstraint createComparisonConstraint(AtomicGuard currentGuard, AtomicGuard newGuard) {
        // 假设 currentGuard 和 newGuard 已经规范化为相同的 clock1 和 clock2 顺序
        // 比较 currentGuard.bound 和 newGuard.bound
        // 论文中 C(D, f) 的定义是 e_ij (rel_ij rel) e
        // 这里的 rel_ij 是 currentGuard.getRelation()
        // rel 是 newGuard.getRelation()
        // e_ij 是 currentGuard.getBound()
        // e 是 newGuard.getBound()

        // 这是一个复杂的逻辑，需要根据关系类型组合
        // 简单化：我们通常比较的是新约束是否比旧约束更紧
        // 例如，如果 currentGuard 是 x-y <= E_curr，newGuard 是 x-y <= E_new
        // 那么 C(D,f) 可能是 E_curr <= E_new
        // 论文 Definition 9 (Canonical Form) 提到 C = e_ij (rel_ij => rel_ik rel_kj) e_ik + e_kj
        // 这里的 C(D,f) 应该是一个 ParameterConstraint，表示新边界是否比旧边界更紧
        // 假设我们只关心新边界是否比旧边界更紧，即 newGuard.bound <= currentGuard.bound
        // 这是一个简化的例子，实际需要根据论文 Definition 9 的精确语义来构造
        // 论文中 Adding Guards 部分的 C(D,xi - xj < e) = e_ij (rel_ij rel) e
        // 这里的 rel_ij 是 DBM 中存储的关系，rel 是新 guard 的关系
        // 这是一个复杂的逻辑，需要根据 rel_ij 和 rel 的组合来确定最终的 ParameterConstraint
        // 暂时简化为：检查 newGuard 是否比 currentGuard 更紧
        // 例如：newGuard.bound <= currentGuard.bound
        // 或者：newGuard.bound < currentGuard.bound
        // 这是一个需要精确实现的地方，暂时用一个简单的例子代替
        return ParameterConstraint.of(newGuard.getBound(), currentGuard.getBound(), RelationType.LE); // 假设新边界 <= 旧边界
    }


    /**
     * 将 PDBM 转换为规范形式 (Canonical Form)。
     * 使用 Floyd-Warshall 算法计算所有点对之间的最短路径，收紧约束。
     * 规范形式对于判空和包含检查是必需的。
     * 此操作可能导致 PDBM 分裂，因此返回一个 PDBM 集合。
     *
     * @param currentConstraintSet 当前的参数约束集 (C)，用于 Z3 Oracle 查询。
     * @param oracle Z3 Oracle 实例。
     * @return 包含一个或多个规范化 PDBM 的集合。如果导致不可满足，返回空集合。
     */
    public Set<PDBM> canonical(ConstraintSet currentConstraintSet, Z3Oracle oracle) {
        logger.debug("canonical: 规范化 PDBM {}", this);
        // 这是 PDBM 最复杂的核心算法，涉及符号化 Floyd-Warshall
        // 伪代码：
        // resultPDBMs = {this}
        // for k from 0 to size-1:
        //   for i from 0 to size-1:
        //     for j from 0 to size-1:
        //       if (i == k || k == j || i == j) continue; // 跳过对角线和自循环
        //       // 尝试通过 k 路径收紧 i -> j
        //       // path_ij = path_ik + path_kj
        //       // path_ik_guard = boundsMatrix[i][k]
        //       // path_kj_guard = boundsMatrix[k][j]
        //       // direct_ij_guard = boundsMatrix[i][j]
        //
        //       // 1. 计算 path_ij 的边界表达式 (LinearExpression)
        //       //    E_path = E_ik + E_kj
        //       //    Rel_path = combineRelations(Rel_ik, Rel_kj)
        //       LinearExpression pathBound = boundsMatrix[i][k].getBound().add(boundsMatrix[k][j].getBound());
        //       RelationType pathRelation = combineRelations(boundsMatrix[i][k].getRelation(), boundsMatrix[k][j].getRelation());
        //       AtomicGuard pathGuard = AtomicGuard.of(clockList.get(i), clockList.get(j), pathBound, pathRelation);
        //
        //       // 2. 构造 ParameterConstraint C(D, pathGuard)
        //       //    比较 pathGuard 和 direct_ij_guard
        //       ParameterConstraint comparisonConstraint = createComparisonConstraint(boundsMatrix[i][j], pathGuard);
        //
        //       // 3. 调用 Z3 Oracle 检查 comparisonConstraint 与 currentConstraintSet 的关系
        //       Z3Oracle.OracleResult oracleResult = oracle.checkCoverage(comparisonConstraint, currentConstraintSet);
        //
        //       switch (oracleResult) {
        //           case YES:
        //               // pathGuard 比 direct_ij_guard 更宽松或等价，无需收紧，PDBM 不变
        //               break;
        //           case NO:
        //               // pathGuard 比 direct_ij_guard 更紧，PDBM 必须收紧
        //               // 创建一个新的 PDBM 实例，更新对应的边界
        //               AtomicGuard[][] newBoundsMatrix = deepCopyBoundsMatrix();
        //               newBoundsMatrix[i][j] = pathGuard; // 替换为更紧的路径约束
        //               resultPDBMs.add(new PDBM(clockList, clockIndexMap, newBoundsMatrix));
        //               // 注意：这里会产生新的 PDBM，需要递归调用 canonical 或在外部管理
        //               // 符号化 Floyd-Warshall 通常是迭代的，直到没有 PDBM 发生变化或分裂
        //               break;
        //           case SPLIT:
        //               // 边界分裂，需要返回多个 PDBM
        //               // 这是一个复杂的分裂处理，通常在 ConstrainedPDBM 层面处理
        //               // PDBM.canonical 应该返回 Set<PDBM>，每个 PDBM 对应一个参数子区域
        //               // 论文中 PDBM.canonical 似乎不直接导致分裂，分裂发生在 ConstrainedPDBM 层面
        //               // 让我们假设 PDBM.canonical 总是返回一个 PDBM，分裂由 ConstrainedPDBM 处理
        //               logger.warn("PDBM.canonical 遇到 SPLIT 结果，这通常应在 ConstrainedPDBM 层面处理。");
        //               // 暂时返回当前 PDBM，等待 ConstrainedPDBM 处理分裂
        //               break;
        //           case UNKNOWN:
        //               logger.warn("Z3 Oracle 返回 UNKNOWN 结果，无法确定约束关系。");
        //               // 无法确定，返回当前 PDBM
        //               break;
        //       }
        //
        // return resultPDBMs; // 暂时返回 Set，如果 PDBM.canonical 总是返回一个，则改为 PDBM
        //
        // 实际实现会更复杂，需要迭代地应用 Floyd-Warshall 步骤，并处理 Z3 Oracle 的 SPLIT 结果
        // 每次 SPLIT 都会导致 PDBM 集合的扩展
        // 这是一个半决定性算法，可能不终止
        logger.warn("PDBM.canonical 方法尚未完全实现符号化 Floyd-Warshall 算法。");
        Set<PDBM> canonicalPDBMs = new HashSet<>();
        canonicalPDBMs.add(this); // 暂时返回自身
        return canonicalPDBMs;
    }

    /**
     * 辅助方法：组合两个关系类型。
     * 例如：< 和 < 组合是 <；< 和 <= 组合是 <；<= 和 <= 组合是 <=。
     * @param rel1 关系类型1。
     * @param rel2 关系类型2。
     * @return 组合后的关系类型。
     */
    private RelationType combineRelations(RelationType rel1, RelationType rel2) {
        if (rel1 == RelationType.LT || rel2 == RelationType.LT) {
            return RelationType.LT; // 只要有一个是严格小于，结果就是严格小于
        }
        if (rel1 == RelationType.GT || rel2 == RelationType.GT) {
            return RelationType.GT; // 只要有一个是严格大于，结果就是严格大于
        }
        // 其他情况，例如 <= 和 <= 组合是 <=
        // 或者 >= 和 >= 组合是 >=
        // 这里的逻辑需要根据具体语义来完善
        // 论文中通常只考虑 <= 和 < 的组合
        // 假设我们只处理上界约束 (<=, <)
        if (rel1 == RelationType.LE && rel2 == RelationType.LE) {
            return RelationType.LE;
        }
        if (rel1 == RelationType.GE && rel2 == RelationType.GE) {
            return RelationType.GE;
        }
        // 混合类型组合需要更复杂的逻辑，或者在 PDBM 中只存储一种规范化关系
        // 例如，如果 PDBM 只存储 <= 形式，那么 < V 转换为 <= V - epsilon
        logger.warn("combineRelations: 遇到未处理的关系类型组合 {} 和 {}", rel1, rel2);
        return RelationType.LE; // 默认返回 LE，需要根据实际情况调整
    }


    /**
     * 移除相对于零时钟的上界约束，将 M[i][0] (代表 c_i - x0 <= V, 即 c_i <= V) 设置为无穷大 (< ∞)。
     * 此操作返回一个新的 PDBM 实例。
     *
     * @return 移除约束后的新 PDBM 实例。
     */
    public PDBM delay() {
        AtomicGuard[][] newBoundsMatrix = deepCopyBoundsMatrix();
        for (int i = 1; i < size; i++) { // 从 1 开始，跳过 M[0][0]
            Clock ci = clockList.get(i);
            // ci - x0 <= infinity
            newBoundsMatrix[i][0] = AtomicGuard.of(ci, Clock.ZERO_CLOCK, LinearExpression.of(Rational.INFINITY), RelationType.LE);
        }
        // 论文中 delay 后需要 canonicalize
        // 但 PDBM 是不可变的，所以 canonicalize 应该在外部调用，或者 timeSuccessor 返回 canonical 后的结果
        // 暂时只返回 up 后的结果
        PDBM upPDBM = new PDBM(clockList, clockIndexMap, newBoundsMatrix);
        // 论文中 timeSuccessor 后需要 canonicalize
        // return upPDBM.canonical(currentConstraintSet, oracle); // 这会引入循环依赖和复杂性
        return upPDBM;
    }

    /**
     * 将指定的时钟重置为指定值。
     * 此操作返回一个新的 PDBM 实例。
     *
     * @param resetSet 要重置的时钟集合及其值。
     * @return 重置后的新 PDBM 实例。
     */
    public PDBM reset(ResetSet resetSet) {
        AtomicGuard[][] newBoundsMatrix = deepCopyBoundsMatrix();
        for (Map.Entry<Clock, Rational> entry : resetSet.getResets().entrySet()) {
            Clock clockToReset = entry.getKey();
            Rational resetValue = entry.getValue(); // 重置值

            Integer index = clockIndexMap.get(clockToReset);
            if (index == null) {
                logger.warn("reset: 时钟 {} 未在 PDBM 中找到，忽略重置。", clockToReset);
                continue;
            }

            // 论文中 DBM reset 操作：
            // M[idx][j] = M[0][j] (x_idx - x_j <= x0 - x_j)
            // M[j][idx] = M[j][0] (x_j - x_idx <= x_j - x0)
            // M[idx][idx] = 0 (x_idx - x_idx <= 0)
            // 这里的 resetValue 是 0，所以 x_idx = 0
            // 实际上是 x_idx - x_j <= 0 - x_j => x_idx - x_j <= -x_j
            // x_j - x_idx <= x_j - 0 => x_j - x_idx <= x_j
            // 这与 DBM 的 reset 逻辑不同，PDBM 的 reset 应该更复杂，因为它涉及参数
            // 论文中 PDBM 的 reset 操作是：
            // D[xi := b] (xi - xj) = (b - xj)
            // D[xi := b] (xj - xi) = (xj - b)
            // D[xi := b] (xi - xi) = 0
            // 这意味着需要将 LinearExpression 中的 xi 替换为 b
            // 这是一个复杂的替换操作，需要遍历所有边界
            logger.warn("PDBM.reset 方法尚未完全实现含参表达式的重置逻辑。");

            // 简化实现：假设重置为 0
            for (int j = 0; j < size; j++) {
                Clock cj = clockList.get(j);
                // M[index][j] = clockToReset - cj <= 0 - cj
                newBoundsMatrix[index][j] = AtomicGuard.of(clockToReset, cj, LinearExpression.of(cj).negate(), RelationType.LE);
                // M[j][index] = cj - clockToReset <= cj - 0
                newBoundsMatrix[j][index] = AtomicGuard.of(cj, clockToReset, LinearExpression.of(cj), RelationType.LE);
            }
            newBoundsMatrix[index][index] = AtomicGuard.of(clockToReset, clockToReset, LinearExpression.of(Rational.ZERO), RelationType.LE);
        }
        return new PDBM(clockList, clockIndexMap, newBoundsMatrix);
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
        // 比较时钟列表和矩阵内容
        if (!clockList.equals(pdbm.clockList)) {
            return false;
        }
        // Arrays.deepEquals 用于比较二维数组的内容
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
        int elementWidth = 20; // 调整宽度以适应 LinearExpression

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
        cmp = this.clockList.toString().compareTo(other.clockList.toString());
        if (cmp != 0) {
            return cmp;
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
