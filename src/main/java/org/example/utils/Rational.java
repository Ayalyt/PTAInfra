package org.example.utils;

import com.microsoft.z3.ArithExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.RealExpr;
import com.microsoft.z3.RealSort;
import lombok.Getter;
import org.example.symbolic.Z3VariableManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.example.utils.RationalType.*;


public final class Rational implements Comparable<Rational> {
    private static final Logger logger = LoggerFactory.getLogger(Rational.class);
    private static final int MAX_CACHE_MAGNITUDE = 1024;
    private static final ConcurrentHashMap<List<BigInteger>, Rational> CACHE = new ConcurrentHashMap<>(2048);
    private static final ForkJoinPool PARALLEL_POOL = new ForkJoinPool();

    // BigInteger 常量
    private static final BigInteger BIG_INT_ZERO = BigInteger.ZERO;
    private static final BigInteger BIG_INT_ONE = BigInteger.ONE;
    private static final BigInteger BIG_INT_NEG_ONE = BigInteger.valueOf(-1);
    private static final BigInteger BIG_INT_TEN = BigInteger.TEN;

    @Getter
    private final BigInteger numerator;
    @Getter
    private final BigInteger denominator;
    @Getter
    private final RationalType type;

    private volatile int hash;

    // 常用常量
    public static final Rational ZERO = new Rational(BIG_INT_ZERO, BIG_INT_ONE, FINITE); // 0/1
    public static final Rational ONE = new Rational(BIG_INT_ONE, BIG_INT_ONE, FINITE);   // 1/1
    public static final Rational HALF = new Rational(BIG_INT_ONE, BigInteger.valueOf(2), FINITE); // 1/2
    public static final Rational INFINITY = new Rational(BIG_INT_ONE, BIG_INT_ZERO, POS_INFINITY); // 1/0
    public static final Rational NEG_INFINITY = new Rational(BIG_INT_NEG_ONE, BIG_INT_ZERO, RationalType.NEG_INFINITY); // -1/0
    public static final Rational NaN = new Rational(BIG_INT_ZERO, BIG_INT_ZERO, NAN); // 0/0

    public static final Rational EPSILON = new Rational(BigInteger.valueOf(1), BigInteger.valueOf(1000000), FINITE);

    static {
        CACHE.put(ZERO.getCacheKey(), ZERO);
        CACHE.put(ONE.getCacheKey(), ONE);
        CACHE.put(HALF.getCacheKey(), HALF);
        CACHE.put(valueOf(-1).getCacheKey(), valueOf(-1));

        for (int i = -16; i <= 16; i++) {
            if (i != 0 && i != 1 && i != -1) {
                Rational r = new Rational(BigInteger.valueOf(i), BIG_INT_ONE, FINITE);
                CACHE.put(r.getCacheKey(), r);
            }
        }
        for (int den = 2; den <= 16; den++) {
            for (int num = -den; num <= den; num++) {
                if (num != 0) {
                    valueOf(num, den);
                }
            }
        }
    }


    /**
     * 私有构造函数，用于内部创建和常量定义。
     */
    private Rational(BigInteger numerator, BigInteger denominator, RationalType type) {
        this.numerator = numerator;
        this.denominator = denominator;
        this.type = type;
        logger.info("创建了一个Rational: {} / {}, 它是{}", numerator, denominator, type);
    }


    // ========== 工厂方法 ==========

    public static Rational valueOf(BigInteger numerator) {
        return valueOf(numerator, BIG_INT_ONE);
    }

    public static Rational valueOf(long numerator) {
        if (numerator == 0L) {
            return ZERO;
        }
        if (numerator == 1L) {
            return ONE;
        }
        return valueOf(BigInteger.valueOf(numerator), BIG_INT_ONE);
    }

    public static Rational valueOf(int numerator) {
        return valueOf((long) numerator);
    }

// 假设类的顶部，缓存的定义已修改为：
// private static final Map<List<BigInteger>, Rational> CACHE = new ConcurrentHashMap<>();

    public static Rational valueOf(BigInteger numerator, BigInteger denominator) {
        logger.info("尝试创建一个Rational: {} / {}", numerator, denominator);

        // 1. 处理分母为0的特殊情况
        if (denominator.signum() == 0) {
            int signNum = numerator.signum();
            if (signNum > 0) {
                return INFINITY;
            }
            if (signNum < 0) {
                return NEG_INFINITY;
            }
            return NaN;
        }

        // 2. 分子为0的情况
        if (numerator.signum() == 0) {
            return ZERO;
        }

        // 3. 分母总是正数
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }

        // 4. 提前处理结果为1的情况，避免GCD
        if (numerator.equals(denominator)) {
            return ONE;
        }

        // 5. 约分
        BigInteger commonDivisor = numerator.gcd(denominator);
        if (!commonDivisor.equals(BIG_INT_ONE)) {
            numerator = numerator.divide(commonDivisor);
            denominator = denominator.divide(commonDivisor);
        }


        // 6. 规范化后检查是否为其他预定义常量
        if (numerator.equals(BIG_INT_ONE) && denominator.equals(BigInteger.valueOf(2))) {
            return HALF;
        }

        // 7. 统一处理缓存
        List<BigInteger> key = List.of(numerator, denominator);

        Rational cached = CACHE.get(key);
        if (cached != null) {
            logger.debug("从缓存命中: {}/{}", numerator, denominator);
            return cached;
        }

        // 8. 缓存未命中
        logger.debug("无缓存值，创建并缓存: {}/{}", numerator, denominator);
        Rational result = new Rational(numerator, denominator, RationalType.FINITE);
        if (shouldCache(result)) {
            CACHE.put(key, result);
        }

        return result;
    }

    public static Rational valueOf(long numerator, long denominator) {
        return valueOf(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    public static Rational valueOf(int numerator, int denominator) {
        return valueOf((long) numerator, (long) denominator);
    }


    public static Rational valueOf(double value) {
        if (Double.isNaN(value)) {
            return NaN;
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? INFINITY : NEG_INFINITY;
        }
        if (Math.abs(value) < 1e-15) {
            return ZERO;
        }
        if (Math.abs(value - 1.0) < 1e-15) {
            return ONE;
        }
        if (Math.abs(value - 0.5) < 1e-15) {
            return HALF;
        }
        if (Math.abs(value + 1.0) < 1e-15) {
            return valueOf(1);
        }
        if (Math.abs(value - -1.0) < 1e-15) {
            return valueOf(-1);
        }

        // 使用 BigDecimal 进行精确转换
        BigDecimal bd = new BigDecimal(Double.toString(value));
        int scale = bd.scale();
        BigInteger num;
        BigInteger den;
        if (scale <= 0) {
            // 整数或科学计数法的大数
            num = bd.unscaledValue().multiply(BIG_INT_TEN.pow(-scale));
            den = BIG_INT_ONE;
        } else {
            num = bd.unscaledValue();
            den = BIG_INT_TEN.pow(scale);
        }

        return valueOf(num, den); // Handles normalization and caching
    }

    public static Rational valueOf(String s) {
        if (s == null || s.trim().isEmpty()) {
            throw new NumberFormatException("输入非法");
        }
        s = s.trim();

//        Rational cached = CACHE.get(s);
//        if (cached != null) {
//            return cached;
//        }
        // 现在的缓存是BigInteger，String一定查不到


        // 处理特殊的无穷符号
        if ("+".equals(s) || "∞".equals(s) || "Infinity".equalsIgnoreCase(s)) {
            return INFINITY;
        }
//        if ("-".equals(s) || "-Infinity".equalsIgnoreCase(s)) {
//            return NEG_INFINITY;
//        } 暂时先不用
        if (s.equalsIgnoreCase("NaN")) {
            return NaN;
        }


        // 处理分数形式
        if (s.contains("/")) {
            String[] parts = s.split("/", 2);
            if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                throw new NumberFormatException("无效分数格式: " + s);
            }
            try {
                BigInteger num = new BigInteger(parts[0].trim());
                BigInteger den = new BigInteger(parts[1].trim());
                return valueOf(num, den);
            } catch (NumberFormatException e) {
                throw new NumberFormatException("分数" + s + "的数字无效: " + e.getMessage());
            }
        }

        // 处理整数和小数 (使用 BigDecimal for robustness)
        try {
            BigDecimal bd = new BigDecimal(s);
            int scale = bd.scale();
            BigInteger num;
            BigInteger den;
            if (scale <= 0) {
                num = bd.unscaledValue().multiply(BIG_INT_TEN.pow(-scale));
                den = BIG_INT_ONE;
            } else {
                num = bd.unscaledValue();
                den = BIG_INT_TEN.pow(scale);
            }
            return valueOf(num, den);

        } catch (NumberFormatException e) {
            throw new NumberFormatException("无效数字格式: " + s);
        }
    }

    // ========== 基础运算 ==========
    public Rational add(Rational other) {
        logger.debug("计算了{} + {}", this, other);
        if (this.isNaN() || other.isNaN()) {
            return NaN;
        }
        if (this == ZERO) {
            return other;
        }
        if (other == ZERO) {
            return this;
        }

        if (this.type == RationalType.POS_INFINITY) {
            return (other.type == RationalType.NEG_INFINITY) ? NaN : INFINITY;
        }
        if (this.type == RationalType.NEG_INFINITY) {
            return (other.type == RationalType.POS_INFINITY) ? NaN : NEG_INFINITY;
        }
        if (other.type != RationalType.FINITE) {
            return other;
        }


        BigInteger num1 = this.numerator; BigInteger den1 = this.denominator;
        BigInteger num2 = other.numerator; BigInteger den2 = other.denominator;

        BigInteger newNum = num1.multiply(den2).add(num2.multiply(den1));
        BigInteger newDen = den1.multiply(den2);

        return valueOf(newNum, newDen);
    }

    public Rational subtract(Rational other) {
        logger.debug("计算了{} - {}", this, other);
        if (this.isNaN() || other.isNaN()) {
            return NaN;
        }
        return this.add(other.negate());
    }

    public Rational multiply(Rational other) {
        logger.debug("计算了{} * {}", this, other);
        if (this.isNaN() || other.isNaN()) {
            return NaN;
        }
        if ((this.isZero() && other.isInfinity()) || (this.isInfinity() && other.isZero())) {
            return NaN;
        }

        int s1 = this.signum();
        int s2 = other.signum();
        if (s1 == 0 || s2 == 0) {
            return ZERO;
        }

        if (this.isInfinity() || other.isInfinity()) {
            return (s1 * s2 > 0) ? INFINITY : NEG_INFINITY;
        }

        // Finite multiplication
        BigInteger num1 = this.numerator; BigInteger den1 = this.denominator;
        BigInteger num2 = other.numerator; BigInteger den2 = other.denominator;
        return valueOf(num1.multiply(num2), den1.multiply(den2));
    }

    public Rational divide(Rational other) {
        logger.debug("计算了{} / {}", this, other);
        if (this.isNaN() || other.isNaN()) {
            return NaN;
        }
        if (other.isZero()) {
            if (this.isZero()) {
                return NaN; // 0 / 0
            }
            return this.signum() > 0 ? INFINITY : NEG_INFINITY; // Finite / 0
        }
        if (other.isInfinity()) {
            return ZERO; // Finite / Inf = 0
        }
        if (this.isInfinity()) {
            // Inf / Finite
            return this.signum() * other.signum() > 0 ? INFINITY : NEG_INFINITY;
        }
        if (other == ONE) {
            return this;
        }
        return this.multiply(other.reciprocal());
    }

    public Rational reciprocal() {
        logger.debug("计算了{}的倒数", this);
        if (isNaN()) {
            return NaN;
        }
        if (isZero()) {
            return INFINITY; // 1 / 0 -> Infinity (sign handled by factory)
        }
        if (this == INFINITY) {
            return ZERO; // 1 / +Inf -> 0
        }
        if (this == NEG_INFINITY) {
            return ZERO; // 1 / -Inf -> 0
        }
        return valueOf(this.denominator, this.numerator);
    }

    public Rational negate() {
        logger.debug("计算了{}的相反数", this);
        return switch (this.type) {
            case NAN -> NaN;
            case POS_INFINITY -> NEG_INFINITY;
            case NEG_INFINITY -> INFINITY;
            case FINITE -> {
                if (this.isZero()) {
                    yield ZERO;
                }
                yield valueOf(this.numerator.negate(), this.denominator);
            }
            default -> throw new IllegalStateException("Unknown type");
        };
    }

    public Rational abs() {
        logger.debug("计算了{}的绝对值", this);
        if (isNaN()) {
            return NaN;
        }
        if (this == NEG_INFINITY) {
            return INFINITY;
        }
        if (this.numerator.signum() >= 0) {
            return this;
        }
        return this.negate();
    }

    public static Rational max(Rational a, Rational b) {
        logger.debug("求了{}和{}中的最大值", a, b);
        if (a.isNaN() || b.isNaN()) {
            return NaN;
        }
        if (a.isInfinity() || b.isInfinity()) {
            return a.isInfinity()? a : b;
        }
        if (a.isNegativeInfinity() || b.isNegativeInfinity()) {
            return a.isNegativeInfinity()? b : a;
        }
        return a.compareTo(b) >= 0? a : b;
    }

    // ========== 并行流支持 ==========
    public static Rational parallelSum(Collection<Rational> numbers) {
        return PARALLEL_POOL.submit(() ->
                numbers.parallelStream()
                        .reduce(Rational.ZERO, Rational::add)
        ).join();
    }

    public static Rational parallelProduct(Collection<Rational> numbers) {
        return PARALLEL_POOL.submit(() ->
                numbers.parallelStream()
                        .reduce(Rational.ONE, Rational::multiply)
        ).join();
    }

    public static List<Rational> parallelMap(List<Rational> input, UnaryOperator<Rational> mapper) {
        return PARALLEL_POOL.submit(() ->
                input.parallelStream()
                        .map(mapper)
                        .collect(Collectors.toList())
        ).join();
    }

    public static List<Rational> parallelBinaryOperation(
            List<Rational> list1,
            List<Rational> list2,
            BinaryOperator<Rational> op) {
        if (list1.size() != list2.size()) {
            throw new IllegalArgumentException("Lists must have same size");
        }
        return PARALLEL_POOL.submit(() ->
                IntStream.range(0, list1.size())
                        .parallel()
                        .mapToObj(i -> op.apply(list1.get(i), list2.get(i)))
                        .collect(Collectors.toList())
        ).join();
    }

    // ========== 工具方法 ==========

    /**
     * 是否为有限数 (既不是 Infinity, -Infinity, 也不是 NaN)
     * @return 如果是有限数返回 true
     */
    public boolean isFinite() {
        return type == RationalType.FINITE;
    }

    /**
     * 是否为无穷大 (包括正无穷和负无穷)
     * @return 是否为无穷大
     */
    public boolean isInfinity() {
        return this.type == RationalType.POS_INFINITY || this.type == RationalType.NEG_INFINITY;
    }

    /**
     * 是否为正无穷大
     * @return 是否为正无穷大
     */
    public boolean isPositiveInfinity() {
        return this.type == RationalType.POS_INFINITY;
    }


    /**
     * 是否为负无穷大
     * @return 是否为负无穷大
     */
    public boolean isNegativeInfinity() {
        return this.type == RationalType.NEG_INFINITY;
    }

    /**
     * 是否为零
     * @return 是否为零
     */
    public boolean isZero() {
        return this == ZERO;
    }

    /**
     * 是否为NaN (Not a Number)
     * @return 是否为NaN
     */
    public boolean isNaN() {
        return this.type == RationalType.NAN;
    }

    /**
     * 获取符号: 1 (正数), -1 (负数), 0 (零),
     * 或者抛出异常 (NaN).
     * 无穷大根据其符号返回 1 或 -1.
     * @return 符号值
     * @throws ArithmeticException 如果是 NaN
     */
    public int signum() {
        return switch (this.type) {
            case NAN -> throw new ArithmeticException("signum of NaN");
            case POS_INFINITY -> 1;
            case NEG_INFINITY -> -1;
            case FINITE -> this.numerator.signum();
            default -> throw new IllegalStateException("Unknown type");
        };
    }


    /**
     * 转换为 double 值.
     * 注意：对于非常大或非常小的有理数可能会丢失精度或溢出为 Infinity/-Infinity.
     * @return double 值
     */
    public double doubleValue() {
        if (isNaN()) {
            return Double.NaN;
        }
        if (this == INFINITY) {
            return Double.POSITIVE_INFINITY;
        }
        if (this == NEG_INFINITY) {
            return Double.NEGATIVE_INFINITY;
        }
        if (isZero()) {
            return 0.0;
        }

        // 使用 BigDecimal 进行转换以获得更好的精度控制
        // 设置一个足够大的精度，但避免无限循环
        int precision = Math.max(100, Math.max(numerator.abs().bitLength(), denominator.abs().bitLength()) + 10);
        BigDecimal numBd = new BigDecimal(this.numerator);
        BigDecimal denBd = new BigDecimal(this.denominator);

        try {
            BigDecimal result = numBd.divide(denBd, precision, RoundingMode.HALF_EVEN);
            return result.doubleValue(); // 可能仍然会溢出为 double 的 Infinity
        } catch (ArithmeticException e) {
            // 如果除法导致非终止小数，直接用 double 除法可能更合适（虽然精度较低）
            return this.numerator.doubleValue() / this.denominator.doubleValue();
        }
    }

    /**
     * 转换为 float 值.
     * 注意：精度损失比 double 更严重.
     * @return float 值
     */
    public float floatValue() {
        return (float) doubleValue();
    }

    /**
     * 转换为 long 值 (截断小数部分).
     * @return long 值
     * @throws ArithmeticException 如果数值超出 long 范围或为非有限数
     */
    public long longValue() {
        if (isInfinity()) {
            logger.error("尝试将无穷大转换为 long");
            throw new ArithmeticException("Rational to long: " + this);
        }
        if (isZero()) {
            return 0L;
        }
        // 执行 BigInteger 除法 (截断)
        BigInteger result = this.numerator.divide(this.denominator);
        try {
            return result.longValueExact();
        } catch (ArithmeticException e) {
            logger.error("Rational to long 溢出: {}", this);
            throw new ArithmeticException("Rational越界: " + this);
        }
    }

    /**
     * 转换为 int 值 (截断小数部分).
     * @return int 值
     * @throws ArithmeticException 如果数值超出 int 范围或为非有限数
     */
    public int intValue() {
        logger.debug("转换{}为int，截断小数", this);
        if (isInfinity()) {
            logger.error("尝试将无穷大转换为 int");
            throw new ArithmeticException("Rational to int: " + this);
        }
        if (isZero()) {
            return 0;
        }
        BigInteger result = this.numerator.divide(this.denominator);
        try {
            return result.intValueExact();
        } catch (ArithmeticException e) {
            logger.error("Rational越界: {}", this);
            throw new ArithmeticException("Rational越界: " + this);
        }
    }

    /**
     *  判断该rational是否是整数
     */
    public boolean isInteger() {
        return this.denominator.equals(BIG_INT_ONE);
    }

    // 关于无限：当前方案是精确表示，并在输入Z3前进行逻辑判断
    public ArithExpr toZ3Real(Context ctx, Z3VariableManager varManager) {
        // 注意：这个方法不应该被调用在无穷大或NaN上。
        // 这种检查应该在调用者那里完成，这里只处理有限数的情况。
        if (this.type != RationalType.FINITE) {
            logger.error("非法调用Rational.toZ3Real: 尝试将非有限数转换为Z3表达式: {}", this);
            throw new IllegalArgumentException("非法调用Rational.toZ3Real: 尝试将非有限数转换为Z3表达式: " + this);
        }

        return ctx.mkReal(this.toString());
    }

    // ========== 对象基础方法 ==========

    @Override
    public int compareTo(Rational other) {
        // 规定NaN > POS_INFINITY > FINITE > NEG_INFINITY
        int thisOrd = (this.type == RationalType.NAN) ? 3 : (this.type == RationalType.POS_INFINITY) ? 2 : (this.type == RationalType.FINITE) ? 1 : 0;
        int otherOrd = (other.type == RationalType.NAN) ? 3 : (other.type == RationalType.POS_INFINITY) ? 2 : (other.type == RationalType.FINITE) ? 1 : 0;

        if (thisOrd != otherOrd) {
            return Integer.compare(thisOrd, otherOrd);
        }

        // 如果类型相同
        if (this.type == RationalType.FINITE) {
            // 比较有限数
            BigInteger ad = this.numerator.multiply(other.denominator);
            BigInteger cb = other.numerator.multiply(this.denominator);
            return ad.compareTo(cb);
        }

        // 如果都是NaN, POS_INF, 或 NEG_INF，则它们相等
        return 0;
    }

    // 建议修改
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Rational that)) {
            return false;
        }
        if (this.type != that.type) {
            return false;
        }
        if (this.type == RationalType.FINITE) {
            return this.numerator.equals(that.numerator) && this.denominator.equals(that.denominator);
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = hash;
        if (h == 0) {
            h = Objects.hash(type, numerator, denominator);
            if (h == 0) {
                h = 1;
            }
            hash = h;
        }
        return h;
    }

    private List<BigInteger> getCacheKey() {
        return List.of(this.numerator, this.denominator);
    }

    /**
     * 决定一个新创建的 (且未在缓存中的) Rational 是否应该被缓存。
     * 可以基于数值大小、分母大小等因素。
     */
    private static boolean shouldCache(Rational r) {
        if (r.isNaN() || r.isInfinity()) {
            return true;
        }
        try {
            int numBitLength = r.numerator.abs().bitLength();
            int denBitLength = r.denominator.bitLength();
            return (numBitLength + denBitLength) < 64;
        } catch (Exception e) {
            return false;
        }
    }


    @Override
    public String toString() {
        if (isNaN()) {
            return "NaN";
        }
        if (this == INFINITY) {
            return "∞";
        }
        if (this == NEG_INFINITY) {
            return "-∞";
        }
        if (isZero()) {
            return "0";
        }
        if (this.denominator.equals(BIG_INT_ONE)) {
            return this.numerator.toString();
        }
        // 分数
        return this.numerator.toString() + "/" + this.denominator.toString();
    }


}

