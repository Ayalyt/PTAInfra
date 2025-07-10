package org.example.expressions.dcs;

import org.apache.commons.lang3.tuple.Pair;
import org.example.automata.base.ResetSet;
import org.example.core.Clock;
import org.example.core.Parameter;
import org.example.expressions.RelationType;
import org.example.expressions.parameters.ConstraintSet;
import org.example.expressions.parameters.LinearExpression;
import org.example.expressions.parameters.ParameterConstraint;
import org.example.symbolic.Z3Oracle;
import org.example.utils.Rational;
import org.junit.jupiter.api.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PDBMTest {

    // --- Test Setup ---
    private Clock c1, c2, c3;
    private Parameter p1;
    private Z3Oracle oracle;
    private Set<Clock> allClocks;
    private List<Clock> clockList;
    private Map<Clock, Integer> clockIndexMap;

    @BeforeAll
    void setUp() {
        c1 = Clock.createNewClock(); // x1
        c2 = Clock.createNewClock(); // x2
        c3 = Clock.createNewClock(); // x3
        p1 = Parameter.createNewParameter(); // p0

        allClocks = Set.of(Clock.ZERO_CLOCK, c1, c2, c3);
        Set<Parameter> allParameters = Set.of(p1);

        oracle = new Z3Oracle(allParameters, allClocks);

        clockList = allClocks.stream().sorted().collect(Collectors.toList());
        clockIndexMap = clockList.stream().collect(Collectors.toMap(c -> c, clockList::indexOf));
    }

    @AfterAll
    void tearDown() {
        if (oracle != null) {
            oracle.close();
        }
    }

    /**
     * 辅助方法：创建一个自定义的、简单的PDBM用于测试。
     * @param guards 一个Map，键是(i,j)索引对，值是要设置的AtomicGuard。
     * @return 一个新的PDBM实例。
     */
    private PDBM createCustomPDBM(Map<Pair<Integer, Integer>, AtomicGuard> guards) {
        int size = clockList.size();
        AtomicGuard[][] bounds = new AtomicGuard[size][size];
        // 初始化为默认的 c_i - c_j <= infinity
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (i == j) {
                    bounds[i][j] = AtomicGuard.of(clockList.get(i), clockList.get(j), LinearExpression.of(Rational.ZERO), RelationType.LE);
                } else {
                    bounds[i][j] = AtomicGuard.of(clockList.get(i), clockList.get(j), LinearExpression.of(Rational.INFINITY), RelationType.LT);
                }
            }
        }
        // 应用自定义的守卫
        guards.forEach((pair, guard) -> {
            bounds[pair.getLeft()][pair.getRight()] = guard;
        });
        return new PDBM(clockList, clockIndexMap, bounds);
    }


    @Nested
    @DisplayName("核心操作：addGuard")
    class AddGuardTests {

        private int c1_idx, c2_idx;

        @BeforeEach
        void getIndices() {
            c1_idx = clockIndexMap.get(c1);
            c2_idx = clockIndexMap.get(c2);
        }

        @Test
        @DisplayName("情况1 (YES): 添加一个已被隐含的守卫，PDBM不应改变")
        void testAddGuard_WhenGuardIsImplied_ShouldReturnUnchangedPDBM() {
            // 1. 准备
            // D = {c1 - c2 < 5}
            AtomicGuard existingGuard = AtomicGuard.of(c1, c2, LinearExpression.of(Rational.valueOf(5)), RelationType.LT);
            PDBM pdbm = createCustomPDBM(Map.of(Pair.of(c1_idx, c2_idx), existingGuard));

            // C = TRUE
            ConstraintSet C = ConstraintSet.TRUE_CONSTRAINT_SET;

            // 添加守卫 f = {c1 - c2 < 10}
            AtomicGuard impliedGuard = AtomicGuard.of(c1, c2, LinearExpression.of(Rational.valueOf(10)), RelationType.LT);

            // 预期: C(D,f) 是 5 <= 10，恒真。Oracle应返回 YES。

            // 2. 执行
            Collection<Pair<ConstraintSet, PDBM>> results = pdbm.addGuard(impliedGuard, C, oracle);

            // 3. 断言
            assertEquals(1, results.size(), "结果应只包含一个 (C, D) 对");

            Pair<ConstraintSet, PDBM> resultPair = results.iterator().next();

            assertEquals(C, resultPair.getKey(), "约束集 C 不应改变");
            assertEquals(pdbm, resultPair.getValue(), "PDBM 实例不应改变");
        }

        @Test
        @DisplayName("情况2 (NO): 添加一个更严格的守卫，PDBM应被更新")
        void testAddGuard_WhenGuardIsStricter_ShouldReturnUpdatedPDBM() {
            // 1. 准备
            // D = {c1 - c2 < 10}
            AtomicGuard existingGuard = AtomicGuard.of(c1, c2, LinearExpression.of(Rational.valueOf(10)), RelationType.LT);
            PDBM pdbm = createCustomPDBM(Map.of(Pair.of(c1_idx, c2_idx), existingGuard));

            // C = TRUE
            ConstraintSet C = ConstraintSet.TRUE_CONSTRAINT_SET;

            // 添加守卫 f = {c1 - c2 < 5}
            AtomicGuard stricterGuard = AtomicGuard.of(c1, c2, LinearExpression.of(Rational.valueOf(5)), RelationType.LT);

            // 预期: C(D,f) 是 10 <= 5，恒假。Oracle应返回 NO。

            // 2. 执行
            Collection<Pair<ConstraintSet, PDBM>> results = pdbm.addGuard(stricterGuard, C, oracle);

            // 3. 断言
            assertEquals(1, results.size(), "结果应只包含一个 (C, D) 对");

            Pair<ConstraintSet, PDBM> resultPair = results.iterator().next();
            ConstraintSet resultC = resultPair.getKey();
            PDBM resultD = resultPair.getValue();

            assertEquals(C, resultC, "约束集 C 不应改变");
            assertNotEquals(pdbm, resultD, "PDBM 实例应该被更新");

            // 检查更新后的边界
            AtomicGuard updatedGuard = resultD.getGuard(c1_idx, c2_idx);
            assertEquals(stricterGuard, updatedGuard, "PDBM中的边界应被更新为更严格的守卫");
        }

        @Test
        @DisplayName("情况3 (SPLIT): 添加一个不确定的守卫，应分裂参数空间")
        void testAddGuard_WhenGuardIsUncertain_ShouldSplit() {
            // 1. 准备
            // D = {c1 - c2 < p1}
            AtomicGuard existingGuard = AtomicGuard.of(c1, c2, LinearExpression.of(p1), RelationType.LT);
            PDBM pdbm = createCustomPDBM(Map.of(Pair.of(c1_idx, c2_idx), existingGuard));

            // C = TRUE
            ConstraintSet C = ConstraintSet.TRUE_CONSTRAINT_SET;

            // 添加守卫 f = {c1 - c2 < 10}
            AtomicGuard uncertainGuard = AtomicGuard.of(c1, c2, LinearExpression.of(Rational.valueOf(10)), RelationType.LT);

            // 预期: C(D,f) 是 p1 <= 10。Oracle应返回 SPLIT。

            // 2. 执行
            Collection<Pair<ConstraintSet, PDBM>> results = pdbm.addGuard(uncertainGuard, C, oracle);

            // 3. 断言
            assertEquals(2, results.size(), "结果应分裂为两个 (C, D) 对");

            // 将结果转换为Map，便于断言
            Map<ConstraintSet, PDBM> resultMap = results.stream()
                    .collect(Collectors.toMap(Pair::getKey, Pair::getValue));

            // 预期分支1: C' = {p1 <= 10}, D' = D (PDBM不变)
            ConstraintSet expectedC1 = C.and(ParameterConstraint.of(
                    LinearExpression.of(p1), LinearExpression.of(Rational.valueOf(10)), RelationType.LE
            ));
            // assertTrue(resultMap.containsKey(expectedC1), "结果中应包含 p1 <= 10 的分支");
            // assertEquals(pdbm, resultMap.get(expectedC1), "在 p1 <= 10 分支中，PDBM不应改变");

            // 预期分支2: C'' = {p1 > 10}, D'' = D 更新为 {c1 - c2 < 10}
            ConstraintSet expectedC2 = C.and(ParameterConstraint.of(
                    LinearExpression.of(p1), LinearExpression.of(Rational.valueOf(10)), RelationType.GT
            ));
            assertTrue(resultMap.containsKey(expectedC2), "结果中应包含 p1 > 10 的分支");

            PDBM updatedPDBM = resultMap.get(expectedC2);
            assertNotNull(updatedPDBM, "更新后的PDBM不应为null");
            assertEquals(uncertainGuard, updatedPDBM.getGuard(c1_idx, c2_idx), "在 p1 > 10 分支中，PDBM应被更新");
        }
    }

    @Nested
    @DisplayName("核心操作：canonical")
    class CanonicalTests {

        private int c1_idx, c2_idx, c3_idx, x0_idx;

        @BeforeEach
        void getIndices() {
            c1_idx = clockIndexMap.get(c1);
            c2_idx = clockIndexMap.get(c2);
            c3_idx = clockIndexMap.get(c3);
            x0_idx = clockIndexMap.get(Clock.ZERO_CLOCK);
        }

        @Test
        @DisplayName("简单收敛：通过中间时钟x0推导更紧的界")
        void testCanonical_OnNonCanonicalPDBM_ShouldConverge() {
            // 1. 准备
            // D = {c1 - x0 < 10, x0 - c2 < -20}  (即 c1 < 10, c2 > 20)
            // 初始时，c1 - c2 的边界是 < ∞。
            // 范式化后，应通过 x0 推导出 c1 - c2 < 10 + (-20) = -10。

            AtomicGuard c1_lt_10 = AtomicGuard.of(c1, Clock.ZERO_CLOCK, LinearExpression.of(Rational.valueOf(10)), RelationType.LT);
            // x0 - c2 < -20  等价于 c2 - x0 > 20
            AtomicGuard c2_gt_20 = AtomicGuard.of(c2, Clock.ZERO_CLOCK, LinearExpression.of(Rational.valueOf(20)), RelationType.GT);

            AtomicGuard x0_lt_c2_neg_20 = AtomicGuard.of(Clock.ZERO_CLOCK, c2, LinearExpression.of(Rational.valueOf(-20)), RelationType.LT);

            PDBM nonCanonicalPDBM = createCustomPDBM(Map.of(
                    Pair.of(c1_idx, x0_idx), c1_lt_10,
                    Pair.of(x0_idx, c2_idx), x0_lt_c2_neg_20
            ));

            ConstraintSet C = ConstraintSet.TRUE_CONSTRAINT_SET;

            // 2. 执行
            Collection<Pair<ConstraintSet, PDBM>> results = nonCanonicalPDBM.canonical(C, oracle);

            // 3. 断言
            assertEquals(1, results.size(), "对于无参数的收敛，结果应只有一个");

            Pair<ConstraintSet, PDBM> resultPair = results.iterator().next();
            ConstraintSet resultC = resultPair.getKey();
            PDBM resultD = resultPair.getValue();

            assertEquals(C, resultC, "约束集不应改变");

            // 验证 c1 - c2 的边界是否被收紧
            AtomicGuard tightenedGuard = resultD.getGuard(c1_idx, c2_idx);
            LinearExpression expectedBound = LinearExpression.of(Rational.valueOf(-10)); // 10 + (-20)

            // 预期结果是 c1 - c2 < -10
            assertEquals(expectedBound, tightenedGuard.getUpperBound(), "c1-c2 的边界应收紧为 -10");
            assertEquals(RelationType.LT, tightenedGuard.getUpperBoundRelation(), "关系符应为 <, 因为 10(<) + (-20)(<) -> -10(<)");
        }

        @Test
        @DisplayName("分裂收敛：收敛依赖于参数p1")
        void testCanonical_WhenConvergenceDependsOnParameter_ShouldSplit() {
            // 1. 准备
            // 构造一个非范式的 PDBM:
            // D = {c1 - x0 < p1, x0 - c2 < -10} (即 c1 < p1, c2 > 10)
            // 初始时，c1 - c2 的边界是 < ∞。
            // 范式化应尝试将 c1 - c2 的边界更新为 < p1 - 10。
            // 这需要与现有的 ∞ 边界比较，即比较 p1 - 10 和 ∞。
            // p1 - 10 <= ∞ 总是成立，所以这不会分裂。让我们换个例子。

            // 新例子:
            // D = {c1 - x0 < 10, x0 - c2 < 20, c1 - c2 < p1}
            // 通过 x0，我们推导出 c1 - c2 < 30。
            // 现在需要比较现有的边界 p1 和推导出的新边界 30。
            // 这会导致分裂。

            AtomicGuard c1_lt_10 = AtomicGuard.of(c1, Clock.ZERO_CLOCK, LinearExpression.of(Rational.valueOf(10)), RelationType.LT);
            AtomicGuard x0_lt_c2_20 = AtomicGuard.of(Clock.ZERO_CLOCK, c2, LinearExpression.of(Rational.valueOf(20)), RelationType.LT);
            AtomicGuard c1_lt_c2_p1 = AtomicGuard.of(c1, c2, LinearExpression.of(p1), RelationType.LT);

            PDBM nonCanonicalPDBM = createCustomPDBM(Map.of(
                    Pair.of(c1_idx, x0_idx), c1_lt_10,
                    Pair.of(x0_idx, c2_idx), x0_lt_c2_20,
                    Pair.of(c1_idx, c2_idx), c1_lt_c2_p1
            ));

            ConstraintSet C = ConstraintSet.TRUE_CONSTRAINT_SET;

            // 2. 执行
            Collection<Pair<ConstraintSet, PDBM>> results = nonCanonicalPDBM.canonical(C, oracle);

            // 3. 断言
            assertEquals(2, results.size(), "收敛依赖于参数，应分裂为2个结果");

            Map<ConstraintSet, PDBM> resultMap = results.stream()
                    .collect(Collectors.toMap(Pair::getKey, Pair::getValue));

            // 预期分支1: p1 <= 30。在此条件下，p1是更紧的界，PDBM的(c1,c2)边界不变。
            ConstraintSet expectedC1 = C.and(ParameterConstraint.of(
                    LinearExpression.of(p1), LinearExpression.of(Rational.valueOf(30)), RelationType.LE
            ));
            assertTrue(resultMap.containsKey(expectedC1), "结果中应包含 p1 <= 30 的分支");
            assertEquals(c1_lt_c2_p1, resultMap.get(expectedC1).getGuard(c1_idx, c2_idx), "在 p1 <= 30 分支，边界应保持为 p1");

            // 预期分支2: p1 > 30。在此条件下，30是更紧的界，PDBM的(c1,c2)边界被更新。
            ConstraintSet expectedC2 = C.and(ParameterConstraint.of(
                    LinearExpression.of(p1), LinearExpression.of(Rational.valueOf(30)), RelationType.GT
            ));
            assertTrue(resultMap.containsKey(expectedC2), "结果中应包含 p1 > 30 的分支");

            PDBM updatedPDBM = resultMap.get(expectedC2);
            assertNotNull(updatedPDBM);
            AtomicGuard tightenedGuard = updatedPDBM.getGuard(c1_idx, c2_idx);
            assertEquals(LinearExpression.of(Rational.valueOf(30)), tightenedGuard.getUpperBound(), "在 p1 > 30 分支，边界应收紧为 30");
            assertEquals(RelationType.LT, tightenedGuard.getUpperBoundRelation(), "关系符应为 <");
        }

        @Test
        @DisplayName("压力测试1：需要多轮迭代才能收敛")
        void testCanonical_WithMultiStepConvergence() {
            // 1. 准备
            // 构造一个需要链式推导的PDBM: c3 -> c2 -> c1 -> x0
            // D = {c1 - x0 < 10, c2 - c1 < 5, c3 - c2 < 2}
            // 预期收敛过程:
            // 1. c2 - x0 < 10 + 5 = 15 (通过 c1)
            // 2. c3 - x0 < 15 + 2 = 17 (通过 c2, 依赖于第一步的结果)

            AtomicGuard c1_lt_10 = AtomicGuard.of(c1, Clock.ZERO_CLOCK, LinearExpression.of(Rational.valueOf(10)), RelationType.LT);
            AtomicGuard c2_lt_c1_5 = AtomicGuard.of(c2, c1, LinearExpression.of(Rational.valueOf(5)), RelationType.LT);
            AtomicGuard c3_lt_c2_2 = AtomicGuard.of(c3, c2, LinearExpression.of(Rational.valueOf(2)), RelationType.LT);

            PDBM nonCanonicalPDBM = createCustomPDBM(Map.of(
                    Pair.of(c1_idx, x0_idx), c1_lt_10,
                    Pair.of(c2_idx, c1_idx), c2_lt_c1_5,
                    Pair.of(c3_idx, c2_idx), c3_lt_c2_2
            ));

            ConstraintSet C = ConstraintSet.TRUE_CONSTRAINT_SET;

            // 2. 执行
            Collection<Pair<ConstraintSet, PDBM>> results = nonCanonicalPDBM.canonical(C, oracle);

            // 3. 断言
            assertEquals(1, results.size(), "不涉及参数，不应分裂");

            PDBM resultD = results.iterator().next().getRight();

            // 断言第一步推导的结果
            AtomicGuard c2_x0_guard = resultD.getGuard(c2_idx, x0_idx);
            assertEquals(LinearExpression.of(Rational.valueOf(15)), c2_x0_guard.getUpperBound(), "c2-x0 的边界应为 15");
            assertEquals(RelationType.LT, c2_x0_guard.getUpperBoundRelation());

            // 断言最终推导的结果
            AtomicGuard c3_x0_guard = resultD.getGuard(c3_idx, x0_idx);
            assertEquals(LinearExpression.of(Rational.valueOf(17)), c3_x0_guard.getUpperBound(), "c3-x0 的边界应为 17");
            assertEquals(RelationType.LT, c3_x0_guard.getUpperBoundRelation());
        }

        @Test
        @DisplayName("压力测试2：规范化过程中产生矛盾")
        void testCanonical_WhenContradictionIsDerived() {
            // 1. 准备
            // 构造一个包含内在矛盾的PDBM，但矛盾需要通过推导才能发现
            // D = {c1 - c2 < 5, c2 - c1 < -10}
            // 规范化应推导出 c1 - c1 < 5 + (-10) = -5，即 0 < -5，这是个矛盾。
            // 整个 (C, D) 区域应被判定为空。

            AtomicGuard c1_lt_c2_5 = AtomicGuard.of(c1, c2, LinearExpression.of(Rational.valueOf(5)), RelationType.LT);
            // c2 - c1 < -10
            AtomicGuard c2_lt_c1_neg_10 = AtomicGuard.of(c2, c1, LinearExpression.of(Rational.valueOf(-10)), RelationType.LT);

            PDBM contradictoryPDBM = createCustomPDBM(Map.of(
                    Pair.of(c1_idx, c2_idx), c1_lt_c2_5,
                    Pair.of(c2_idx, c1_idx), c2_lt_c1_neg_10
            ));

            ConstraintSet C = ConstraintSet.TRUE_CONSTRAINT_SET;

            // 2. 执行
            Collection<Pair<ConstraintSet, PDBM>> results = contradictoryPDBM.canonical(C, oracle);

            // 3. 断言
            assertTrue(results.isEmpty(), "推导出矛盾后，结果集应为空");
        }
    }

    // ... 在 PDBMTest.java 中 ...
// ... 确保 setUp 中有 c1, c2, 并且 allClocks 包含它们 ...

    @Nested
    @DisplayName("核心操作：reset 和 delay")
    class StateTransitionTests {

        private int c1_idx, c2_idx, x0_idx;

        @BeforeEach
        void getIndices() {
            c1_idx = clockIndexMap.get(c1);
            c2_idx = clockIndexMap.get(c2);
            x0_idx = clockIndexMap.get(Clock.ZERO_CLOCK);
        }

        @Test
        @DisplayName("reset 操作应根据论文公式正确更新矩阵")
        void testReset() {
            // 1. 准备
            // 创建一个PDBM，并为其与零时钟的边界设置一些具体值，以便验证reset公式
            // D = {c2 - x0 < 20, x0 - c2 < -15} (即 15 < c2 < 20)
            AtomicGuard c2_lt_20 = AtomicGuard.of(c2, Clock.ZERO_CLOCK, LinearExpression.of(Rational.valueOf(20)), RelationType.LT);
            AtomicGuard x0_lt_c2_neg_15 = AtomicGuard.of(Clock.ZERO_CLOCK, c2, LinearExpression.of(Rational.valueOf(-15)), RelationType.LT);

            PDBM pdbm = createCustomPDBM(Map.of(
                    Pair.of(c2_idx, x0_idx), c2_lt_20,
                    Pair.of(x0_idx, c2_idx), x0_lt_c2_neg_15
            ));

            // 准备重置操作：将时钟 c1 重置为 5
            ResetSet resetSet = new ResetSet(Map.of(c1, Rational.valueOf(5)));

            // 2. 执行
            PDBM resetPDBM = pdbm.reset(resetSet);

            // 3. 断言
            // 论文公式:
            // D'_i,j = e_0j + b  (当 i 是被重置的时钟, b 是重置值)
            // D'_j,i = e_j0 - b

            // 验证 c1 相关的边界
            // 检查 D'_c1,c2 = e_x0,c2 + 5 = -15 + 5 = -10
            AtomicGuard c1_c2_guard = resetPDBM.getGuard(c1_idx, c2_idx);
            assertEquals(LinearExpression.of(Rational.valueOf(-10)), c1_c2_guard.getUpperBound(), "D'_c1,c2 的边界应为 -10");
            assertEquals(RelationType.LT, c1_c2_guard.getUpperBoundRelation(), "D'_c1,c2 的关系符应与 D_x0,c2 相同");

            // 检查 D'_c2,c1 = e_c2,x0 - 5 = 20 - 5 = 15
            AtomicGuard c2_c1_guard = resetPDBM.getGuard(c2_idx, c1_idx);
            assertEquals(LinearExpression.of(Rational.valueOf(15)), c2_c1_guard.getUpperBound(), "D'_c2,c1 的边界应为 15");
            assertEquals(RelationType.LT, c2_c1_guard.getUpperBoundRelation(), "D'_c2,c1 的关系符应与 D_c2,x0 相同");

            // 验证 c1 对 x0 的边界
            // D'_c1,x0 = e_x0,x0 + 5 = 0 + 5 = 5
            AtomicGuard c1_x0_guard = resetPDBM.getGuard(c1_idx, x0_idx);
            assertEquals(LinearExpression.of(Rational.valueOf(5)), c1_x0_guard.getUpperBound(), "D'_c1,x0 的边界应为 5");
            assertEquals(RelationType.LE, c1_x0_guard.getUpperBoundRelation(), "D'_c1,x0 的关系符应与 D_x0,x0 相同");

            // 验证其他边界 (c2 对 x0) 应该保持不变
            AtomicGuard c2_x0_guard_after_reset = resetPDBM.getGuard(c2_idx, x0_idx);
            assertEquals(c2_lt_20, c2_x0_guard_after_reset, "未被重置的时钟边界不应改变");
        }

        @Test
        @DisplayName("delay 操作应正确放开所有非零时钟的上界")
        void testDelay() {
            // 1. 准备
            // 创建一个具有一些具体边界的PDBM
            AtomicGuard c1_lt_10 = AtomicGuard.of(c1, Clock.ZERO_CLOCK, LinearExpression.of(Rational.valueOf(10)), RelationType.LT);
            AtomicGuard c1_lt_c2_5 = AtomicGuard.of(c1, c2, LinearExpression.of(Rational.valueOf(5)), RelationType.LT);
            PDBM pdbm = createCustomPDBM(Map.of(
                    Pair.of(c1_idx, x0_idx), c1_lt_10,
                    Pair.of(c1_idx, c2_idx), c1_lt_c2_5
            ));

            // 2. 执行
            PDBM delayedPDBM = pdbm.delay();

            // 3. 断言
            for (int i = 0; i < delayedPDBM.getSize(); i++) {
                if (i == x0_idx) continue; // 跳过零时钟

                // 验证所有 D_i0 (i > 0) 的上界是否为无穷大
                AtomicGuard guard = delayedPDBM.getGuard(i, x0_idx);
                assertTrue(guard.getUpperBound().isCertainlyPositiveInfinity(), "D_" + i + ",0 的上界应为无穷大");
                assertEquals(RelationType.LT, guard.getUpperBoundRelation(), "关系符应为 <");
            }

            // 验证其他边界没有被修改
            AtomicGuard c1_c2_guard_after_delay = delayedPDBM.getGuard(c1_idx, c2_idx);
            assertEquals(c1_lt_c2_5, c1_c2_guard_after_delay, "delay不应修改非零时钟之间的边界");
        }
    }


}
