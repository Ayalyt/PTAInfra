package org.example.symbolic;

import com.microsoft.z3.ArithExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.RealExpr;
import com.microsoft.z3.Solver;
import lombok.Getter;
import org.example.core.Clock;
import org.example.core.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 负责管理 Java Parameter 和 Clock 对象到 Z3 ArithExpr 变量的映射。
 * 确保每个 Java 变量在 Z3 Context 中有唯一的对应 Z3 变量。
 * 在 Solver 初始化时，断言零时钟和所有已知时钟的非负约束。
 * @author Ayalyt
 */
@Getter
public class Z3VariableManager {

    private static final Logger logger = LoggerFactory.getLogger(Z3VariableManager.class);

    private final Context ctx;
    // 使用 HashMap 存储映射，因为这个实例是 ThreadLocal 的，不会有并发问题
    private final Map<Parameter, ArithExpr> paramZ3Vars;
    private final Map<Clock, ArithExpr> clockZ3Vars;

    // 存储所有已知的 Parameter 和 Clock 集合，用于在 Solver 初始化时断言全局约束
    private final Set<Parameter> allKnownParameters;
    private final Set<Clock> allKnownClocks;

    private final RealExpr posInfZ3;
    private final RealExpr negInfZ3;


    /**
     * 构造函数。
     * @param ctx Z3 Context 实例。
     * @param allParameters PTA 中所有参数的集合。
     * @param allClocks PTA 中所有时钟的集合。
     */
    public Z3VariableManager(Context ctx, Set<Parameter> allParameters, Set<Clock> allClocks) {
        this.ctx = Objects.requireNonNull(ctx, "Z3 Context cannot be null.");
        this.allKnownParameters = Collections.unmodifiableSet(new HashSet<>(allParameters));
        this.allKnownClocks = Collections.unmodifiableSet(new HashSet<>(allClocks));

        this.paramZ3Vars = new HashMap<>();
        this.clockZ3Vars = new HashMap<>();

        this.posInfZ3 = (RealExpr) ctx.mkConst("∞", ctx.mkRealSort());
        this.negInfZ3 = (RealExpr) ctx.mkConst("-∞", ctx.mkRealSort());

        // 预先创建所有已知参数和时钟的 Z3 变量，并缓存
        // 这样在 assertZeroClockConstraint 和其他地方使用时，变量已经存在
        for (Parameter param : allKnownParameters) {
            getZ3Var(param); // 调用 getZ3Var 会创建并缓存
        }
        for (Clock clock : allKnownClocks) {
            getZ3Var(clock); // 调用 getZ3Var 会创建并缓存
        }

        logger.debug("Z3VariableManager 初始化完成，管理 {} 个参数，{} 个时钟。",
                allKnownParameters.size(), allKnownClocks.size());
    }

    /**
     * 获取指定 Parameter 对应的 Z3 ArithExpr 变量。
     * 如果变量尚未创建，则会创建并缓存。
     * @param param Java Parameter 对象。
     * @return 对应的 Z3 ArithExpr 变量。
     */
    public ArithExpr getZ3Var(Parameter param) {
        return paramZ3Vars.computeIfAbsent(param, p -> {
            logger.info("创建 Z3 参数变量: {}", p.getName());
            return ctx.mkRealConst(p.getName());
        });
    }

    /**
     * 获取指定 Clock 对应的 Z3 ArithExpr 变量。
     * 如果变量尚未创建，则会创建并缓存。
     * @param clock Java Clock 对象。
     * @return 对应的 Z3 ArithExpr 变量。
     */
    public ArithExpr getZ3Var(Clock clock) {
        return clockZ3Vars.computeIfAbsent(clock, c -> {
            logger.info("创建 Z3 时钟变量: {}", c.getName());
            return ctx.mkRealConst(c.getName());
        });
    }

    /**
     * 向 Solver 断言零时钟 (x0) 的约束 (x0 == 0)
     * 和所有已知普通时钟的非负约束 (xi >= 0)。
     * 此方法应在 Solver 首次初始化时调用。
     * @param solver Z3 Solver 实例。
     */
    public void assertGlobalConstraints(Solver solver) {
        // 断言 x0 == 0
        solver.add(ctx.mkEq(getZ3Var(Clock.ZERO_CLOCK), ctx.mkReal(0)));
        logger.info("断言 Z3 约束: {} == 0", Clock.ZERO_CLOCK.getName());

        // 断言所有普通时钟 >= 0
        for (Clock clock : allKnownClocks) {
            if (!clock.isZeroClock()) {
                ArithExpr z3ClockVar = getZ3Var(clock);
                solver.add(ctx.mkGe(z3ClockVar, ctx.mkReal(0)));
                logger.info("断言 Z3 约束: {} >= 0", clock.getName());
            }
        }

        // 断言所有参数 >= 0 (论文中参数是非负实数)
        for (Parameter param : allKnownParameters) {
            ArithExpr z3ParamVar = getZ3Var(param);
            solver.add(ctx.mkGe(z3ParamVar, ctx.mkReal(0)));
            logger.info("断言 Z3 约束: {} >= 0", param.getName());
        }
    }
}
