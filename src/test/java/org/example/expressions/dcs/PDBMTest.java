package org.example.expressions.dcs;

import org.apache.commons.lang3.tuple.Pair;
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
    private Clock c1, c2;
    private Parameter p1;
    private Z3Oracle oracle;
    private Set<Clock> allClocks;
    private List<Clock> clockList;
    private Map<Clock, Integer> clockIndexMap;

    @BeforeAll
    void setUp() {
        c1 = Clock.createNewClock(); // x1
        c2 = Clock.createNewClock(); // x2
        p1 = Parameter.createNewParameter(); // p0

        allClocks = Set.of(Clock.ZERO_CLOCK, c1, c2);
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
}
