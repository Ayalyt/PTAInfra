package org.example.expressions.dcs;

import org.example.core.Clock;
import org.example.core.Parameter;
import org.example.expressions.RelationType;
import org.example.expressions.dcs.AtomicGuard;
import org.example.expressions.parameters.LinearExpression;
import org.example.utils.Rational;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AtomicGuardTest {

    // --- Test Setup ---
    // 在所有测试前创建一些共享的、不可变的对象
    private static Clock c1, c2, c3;
    private static Parameter p1;
    private static LinearExpression constBound5;
    private static LinearExpression paramBoundP1;

    @BeforeAll
    static void setUp() {
        c1 = Clock.createNewClock();
        c2 = Clock.createNewClock();
        c3 = Clock.createNewClock();

        p1 = Parameter.createNewParameter();

        // 创建一些常用的边界表达式
        constBound5 = LinearExpression.of(Rational.valueOf(5));
        paramBoundP1 = LinearExpression.of(p1);
    }

    @Nested
    @DisplayName("构造与规范化 (Construction and Normalization)")
    class ConstructionTests {

        @Test
        @DisplayName("当时钟有序时，不应翻转 (c1 - c2 < 5)")
        void testConstruction_WithOrderedClocks_ShouldNotFlip() {
            AtomicGuard guard = AtomicGuard.of(c1, c2, constBound5, RelationType.LT);

            assertAll("Ordered clocks should not be flipped",
                    () -> assertEquals(c1, guard.getClock1(), "clock1 should be c1"),
                    () -> assertEquals(c2, guard.getClock2(), "clock2 should be c2"),
                    () -> assertEquals(constBound5, guard.getBound(), "Bound should remain the same"),
                    () -> assertEquals(RelationType.LT, guard.getRelation(), "Relation should remain the same"),
                    () -> assertFalse(guard.isFliped(), "isFlipped should be false")
            );
        }

        @Test
        @DisplayName("当时钟逆序时，应自动翻转 (c2 - c1 < 5  =>  c1 - c2 > -5)")
        void testConstruction_WithReversedClocks_ShouldFlip() {
            AtomicGuard guard = AtomicGuard.of(c2, c1, constBound5, RelationType.LT);

            assertAll("Reversed clocks should be flipped and normalized",
                    () -> assertEquals(c1, guard.getClock1(), "clock1 should be normalized to c1"),
                    () -> assertEquals(c2, guard.getClock2(), "clock2 should be normalized to c2"),
                    () -> assertEquals(constBound5.negate(), guard.getBound(), "Bound should be negated"),
                    () -> assertEquals(RelationType.GT, guard.getRelation(), "Relation should be flipped to GT"),
                    () -> assertTrue(guard.isFliped(), "isFlipped should be true")
            );
        }

        @Test
        @DisplayName("使用零时钟的工厂方法应正确创建 (x1 <= 5)")
        void testFactoryMethods_WithZeroClock() {
            AtomicGuard guard = AtomicGuard.lessEqual(c1, Rational.valueOf(5)); // c1 <= 5  =>  c1 - x0 <= 5

            assertAll("Factory method for x1 <= 5",
                    () -> assertEquals(Clock.ZERO_CLOCK, guard.getClock1(), "clock1 should be the zero clock"),
                    () -> assertEquals(c1, guard.getClock2(), "clock2 should be x1"),
                    () -> assertEquals(constBound5.negate(), guard.getBound(), "Bound should be -5"),
                    () -> assertEquals(RelationType.GE, guard.getRelation(), "Relation should be LE")
            );
        }

        @Test
        @DisplayName("构造自身矛盾的约束应抛出异常 (c1 - c1 < 0)")
        void testConstruction_WithContradiction_ShouldThrowException() {
            // c1 - c1 < 0 is a contradiction
            assertThrows(IllegalArgumentException.class, () -> {
                AtomicGuard.of(c1, c1, LinearExpression.of(Rational.ZERO), RelationType.LT);
            }, "c1 - c1 < 0 should throw IllegalArgumentException");

            // c1 - c1 >= 1 is a contradiction
            assertThrows(IllegalArgumentException.class, () -> {
                AtomicGuard.of(c1, c1, LinearExpression.of(Rational.ONE), RelationType.GE);
            }, "c1 - c1 >= 1 should throw IllegalArgumentException");
        }

        @Test
        @DisplayName("构造自身为恒真的约束不应抛出异常 (c1 - c1 <= 0)")
        void testConstruction_WithTautology_ShouldNotThrowException() {
            // c1 - c1 <= 0 is always true
            assertDoesNotThrow(() -> {
                AtomicGuard.of(c1, c1, LinearExpression.of(Rational.ZERO), RelationType.LE);
            });
        }
    }

    @Nested
    @DisplayName("逻辑操作 (Logical Operations)")
    class LogicalOperationTests {

        @Test
        @DisplayName("取反操作应正确 (c1 - c2 < 5  =>  c1 - c2 >= 5)")
        void testNegate() {
            AtomicGuard original = AtomicGuard.of(c1, c2, constBound5, RelationType.LT);
            AtomicGuard negated = original.negate();

            assertAll("Negation should flip relation type",
                    () -> assertEquals(c1, negated.getClock1()),
                    () -> assertEquals(c2, negated.getClock2()),
                    () -> assertEquals(constBound5, negated.getBound()),
                    () -> assertEquals(RelationType.GE, negated.getRelation(), "Relation should be negated from LT to GE")
            );
        }
    }

    @Nested
    @DisplayName("上界表示法 (Upper Bound Representation)")
    class UpperBoundTests {

        @Test
        @DisplayName("对于已经是上界的约束，表示不变 (x1 - x2 <= p0)")
        void testUpperBound_ForExistingUpperBound() {
            AtomicGuard guard = AtomicGuard.of(c1, c2, paramBoundP1, RelationType.LE);

            assertAll("Upper bound representation for x1 - x2 <= p0",
                    () -> assertEquals(c1, guard.getUpperBoundClock1()),
                    () -> assertEquals(c2, guard.getUpperBoundClock2()),
                    () -> assertEquals(paramBoundP1, guard.getUpperBound()),
                    () -> assertEquals(RelationType.LE, guard.getUpperBoundRelation())
            );
        }

        @Test
        @DisplayName("对于下界约束，应转换为上界 (c2 - c3 > 5  =>  c3 - c2 < -5)")
        void testUpperBound_ForLowerBound() {
            // c2 - c3 > 5 is a lower bound on c2-c3.
            // After construction normalization: c2 - c3 > 5 (no flip)
            AtomicGuard guard = AtomicGuard.of(c2, c3, constBound5, RelationType.GT);

            assertAll("Upper bound representation for c2 - c3 > 5",
                    () -> assertEquals(c3, guard.getUpperBoundClock1(), "Upper bound clock1 should be c3"),
                    () -> assertEquals(c2, guard.getUpperBoundClock2(), "Upper bound clock2 should be c2"),
                    () -> assertEquals(constBound5.negate(), guard.getUpperBound(), "Upper bound should be -5"),
                    () -> assertEquals(RelationType.LT, guard.getUpperBoundRelation(), "Upper bound relation should be LT")
            );
        }
    }

    @Nested
    @DisplayName("对象方法 (Object Methods)")
    class ObjectMethodTests {

        @Test
        @DisplayName("equals 和 hashCode 应基于规范化形式")
        void testEqualsAndHashCode_BasedOnNormalization() {
            // These two guards are logically equivalent and should be equal after normalization
            AtomicGuard guard1 = AtomicGuard.of(c1, c2, constBound5, RelationType.LT); // c1 - c2 < 5
            AtomicGuard guard2 = AtomicGuard.of(c2, c1, constBound5.negate(), RelationType.GT); // c2 - c1 > -5 => c1 - c2 < 5

            assertEquals(guard1, guard2, "Logically equivalent guards should be equal");
            assertEquals(guard1.hashCode(), guard2.hashCode(), "Hash codes of equal guards should be equal");
        }

        @Test
        @DisplayName("equals 应能区分不同的约束")
        void testEquals_ForDifferentGuards() {
            AtomicGuard guard1 = AtomicGuard.of(c1, c2, constBound5, RelationType.LT);
            AtomicGuard guard2 = AtomicGuard.of(c1, c2, constBound5, RelationType.LE); // Different relation
            AtomicGuard guard3 = AtomicGuard.of(c1, c3, constBound5, RelationType.LT); // Different clock
            AtomicGuard guard4 = AtomicGuard.of(c1, c2, paramBoundP1, RelationType.LT); // Different bound

            assertNotEquals(guard1, guard2);
            assertNotEquals(guard1, guard3);
            assertNotEquals(guard1, guard4);
            assertNotEquals(guard1, null);
            assertNotEquals(guard1, new Object());
        }

        @Test
        @DisplayName("toString 应生成可读的字符串")
        void testToString() {
            AtomicGuard guard = AtomicGuard.of(c1, c2, paramBoundP1, RelationType.GE);
            assertEquals("x1 - x2 >= p0", guard.toString());
        }

        @Test
        @DisplayName("toUpperBoundString 应生成规范化的上界字符串")
        void testToUpperBoundString() {
            // c2 - c1 > 5  => normalized to c1 - c2 < -5
            AtomicGuard guard = AtomicGuard.of(c2, c1, constBound5, RelationType.GT);
            assertEquals("x1 - x2 < -5", guard.toUpperBoundString());
        }
    }
}
