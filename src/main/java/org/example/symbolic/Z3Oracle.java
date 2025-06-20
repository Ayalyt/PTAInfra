package org.example.symbolic;

import com.microsoft.z3.*;
import org.example.core.Clock;
import org.example.core.ClockValuation;
import org.example.core.Parameter;
import org.example.core.ParameterValuation;
import org.example.expressions.parameters.ParameterConstraint;
import org.example.expressions.parameters.ConstraintSet;
import org.example.utils.Rational;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.*;

/**
 * @author Ayalyt
 */
public class Z3Oracle implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(Z3Oracle.class);

    // 为每个线程提供独立的 Z3 Context
    private final ThreadLocal<Context> threadCtx;
    // 为每个线程提供独立的 Z3 Solver，增量求解
    private final ThreadLocal<Solver> threadSolver;
    // 为每个线程提供独立的 Z3VariableManager
    private final ThreadLocal<Z3VariableManager> threadVarManager;

    private final Set<Parameter> allParameters;
    private final Set<Clock> allClocks;

    /**
     * 创建一个新的 Z3Oracle 实例。
     * 初始化 ThreadLocal，以便每个线程首次使用时创建自己的 Z3 Context、Solver 和 Z3VariableManager。
     */
    public Z3Oracle(Set<Parameter> allParameters, Set<Clock> allClocks) {
        this.allParameters = Objects.requireNonNull(allParameters, "All parameters set cannot be null.");
        this.allClocks = Objects.requireNonNull(allClocks, "All clocks set cannot be null.");

        // 确保 x0 包含在 allClocks 中
        if (!this.allClocks.contains(Clock.ZERO_CLOCK)) {
            throw new IllegalArgumentException("Z3Oracle 必须管理零时钟 (x0)。请确保 allClocks 包含 Clock.ZERO_CLOCK。");
        }

        this.threadCtx = ThreadLocal.withInitial(() -> {
            logger.debug("为线程 {} 初始化 Z3 Context。", Thread.currentThread().getId());
            return new Context();
        });

        // 先初始化 varManager，因为它被 threadSolver 依赖
        this.threadVarManager = ThreadLocal.withInitial(() -> {
            logger.debug("为线程 {} 初始化 Z3VariableManager。", Thread.currentThread().getId());
            // Z3VariableManager 构造时传入所有参数和时钟
            return new Z3VariableManager(threadCtx.get(), this.allParameters, this.allClocks);
        });

        this.threadSolver = ThreadLocal.withInitial(() -> {
            Context ctx = threadCtx.get();
            Solver solver = ctx.mkSolver();
            Z3VariableManager varManager = threadVarManager.get(); // 获取当前线程的 varManager
            // 在 Solver 初始化时断言所有全局约束 (x0 == 0, xi >= 0, pi >= 0)
            varManager.assertGlobalConstraints(solver);
            logger.debug("为线程 {} 初始化 Z3 Solver。", Thread.currentThread().getId());
            return solver;
        });
    }

    /**
     * 获取当前线程的 Z3 Context。
     * @return 当前线程关联的 Z3 Context。
     */
    public Context getContext() { // 保持 public，因为 toZ3BoolExpr 方法需要 Context
        return threadCtx.get();
    }

    /**
     * 获取当前线程的 Z3 Solver。
     * @return 当前线程关联的 Z3 Solver。
     */
    private Solver getSolver() {
        return threadSolver.get();
    }

    /**
     * 获取当前线程的 Z3VariableManager。
     * @return 当前线程关联的 Z3VariableManager。
     */
    public Z3VariableManager getVarManager() { // 保持 public，因为 toZ3BoolExpr 方法需要 VarManager
        return threadVarManager.get();
    }

    // --- 核心约束检查方法 (使用增量 Solver) ---

    /**
     * 检查给定的 Z3 布尔公式是否可满足。
     * 使用增量求解，在当前线程的 Solver 上执行 push/pop。
     *
     * @param formula 要检查的 Z3 布尔公式。
     * @return Status.SAT, Status.UNSAT, 或 Status.UNKNOWN。
     */
    public Status check(BoolExpr formula) {
        Solver solver = getSolver();
        List<BoolExpr> addedAssertions = new ArrayList<>(); // 调试日志

        try {
            solver.push(); // 回溯点
            solver.add(formula);
            addedAssertions.add(formula);

            Status status = solver.check();
            logger.debug("Z3 check for formula: {} -> Status: {}", formula, status);
            return status;

        } catch (Z3Exception e) {
            handleZ3Exception("check(BoolExpr)", e, addedAssertions);
            throw new Z3OracleException("Z3 错误 (check(BoolExpr)): " + e.getMessage(), e);
        } catch (Exception e) {
            handleGenericException("check(BoolExpr)", e, addedAssertions);
            throw new Z3OracleException("意外错误 (check(BoolExpr)): " + e.getMessage(), e);
        } finally {
            popSolver(solver); // 统一处理 pop
        }
    }

    /**
     * 检查一个参数约束集是否可满足。
     * @param constraintSet 参数约束集。
     * @return true 如果可满足，false 如果不可满足，null 如果未知。
     */
    public Boolean isSatisfiable(ConstraintSet constraintSet) {
        Status status = check(constraintSet.toZ3BoolExpr(getContext(), getVarManager()));
        if (status == Status.SATISFIABLE) {
            return true;
        }
        if (status == Status.UNSATISFIABLE) {
            return false;
        }
        return null; // UNKNOWN
    }

    /**
     * 检查一个参数约束 c 是否覆盖约束集 C (即 [C] ⊆ [c])。
     * @param c 原子参数约束。
     * @param C 参数约束集。
     * @return OracleResult.YES, OracleResult.NO, OracleResult.SPLIT, 或 OracleResult.UNKNOWN。
     */
    public OracleResult checkCoverage(ParameterConstraint c, ConstraintSet C) {
        Context ctx = getContext();
        Z3VariableManager varManager = getVarManager();

        BoolExpr C_z3 = C.toZ3BoolExpr(ctx, varManager);
        BoolExpr c_z3 = c.toZ3BoolExpr(ctx, varManager);
        BoolExpr not_c_z3 = ctx.mkNot(c_z3);

        // 检查 C /\ c 是否可满足
        Status status_C_and_c = check(ctx.mkAnd(C_z3, c_z3));
        // 检查 C /\ ¬c 是否可满足
        Status status_C_and_not_c = check(ctx.mkAnd(C_z3, not_c_z3));

        if (status_C_and_c == Status.UNSATISFIABLE) {
            return OracleResult.NO;
        }
        if (status_C_and_not_c == Status.UNSATISFIABLE) {
            return OracleResult.YES;
        }
        if (status_C_and_c == Status.SATISFIABLE && status_C_and_not_c == Status.SATISFIABLE) {
            return OracleResult.SPLIT;
        }

        logger.warn("Z3 Oracle 遇到 UNKNOWN 状态或不确定结果：C&c={}, C&!c={}", status_C_and_c, status_C_and_not_c);
        return OracleResult.UNKNOWN;
    }

    /**
     * 从 Z3 模型中提取 ParameterValuation 和 ClockValuation。
     * @param model Z3 模型。
     * @return 包含 ParameterValuation 和 ClockValuation 的 Map.Entry。
     */
    public Map.Entry<ParameterValuation, ClockValuation> extractModelValuation(Model model) {
        Z3VariableManager varManager = getVarManager(); // 获取当前线程的 varManager
        Map<Parameter, Rational> paramValues = new HashMap<>();
        for (Parameter param : varManager.getAllKnownParameters()) { // 从 varManager 获取所有参数
            Expr z3Var = varManager.getZ3Var(param);
            Expr valueExpr = model.getConstInterp(z3Var);
            if (valueExpr != null) {
                paramValues.put(param, convertZ3ExprToRational(valueExpr));
            } else {
                logger.warn("Z3 模型中缺少参数 {} 的赋值，默认为 0。", param.getName());
                paramValues.put(param, Rational.ZERO);
            }
        }
        Map<Clock, Rational> clockValues = new HashMap<>();
        for (Clock clock : varManager.getAllKnownClocks()) { // 从 varManager 获取所有时钟
            if (clock.isZeroClock()) {
                continue; // x0 的值固定为 0，不从模型中提取
            }
            Expr z3Var = varManager.getZ3Var(clock);
            Expr valueExpr = model.getConstInterp(z3Var);
            if (valueExpr != null) {
                clockValues.put(clock, convertZ3ExprToRational(valueExpr));
            } else {
                logger.warn("Z3 模型中缺少时钟 {} 的赋值，默认为 0。", clock.getName());
                clockValues.put(clock, Rational.ZERO);
            }
        }
        return Map.entry(ParameterValuation.of(paramValues), ClockValuation.of(clockValues));
    }
    /**
     * 将 Z3 表达式转换为 Rational。
     * @param expr Z3 表达式。
     * @return 对应的 Rational 值。
     * @throws IllegalArgumentException 如果表达式不是实数或无法转换。
     */
    private Rational convertZ3ExprToRational(Expr expr) {
        if (expr.isRatNum()) {
            RatNum ratNum = (RatNum) expr;
            BigInteger numerator = new BigInteger(ratNum.getNumerator().toString());
            BigInteger denominator = new BigInteger(ratNum.getDenominator().toString());
            return Rational.valueOf(numerator, denominator);
        } else if (expr.isNumeral()) { // 整数或小数
            // Z3 的 numeral 可能是整数或分数，但 getNumerator/getDenominator 适用于所有实数 numeral
            RatNum ratNum = (RatNum) expr; // 强制转换为 RatNum
            BigInteger numerator = new BigInteger(ratNum.getNumerator().toString());
            BigInteger denominator = new BigInteger(ratNum.getDenominator().toString());
            return Rational.valueOf(numerator, denominator);
        } else {
            logger.error("无法将 Z3 表达式 {} (类型: {}) 转换为 Rational。", expr, expr.getClass().getName());
            throw new IllegalArgumentException("无法将 Z3 表达式转换为 Rational: " + expr);
        }
    }


    /**
     * 枚举 Z3 Oracle 的结果。同论文：YES NO SPLIT
     */
    public enum OracleResult {
        YES, NO, SPLIT, UNKNOWN
    }

    // --- 资源管理 ---

    /**
     * 清理与当前线程关联的 Z3 Context 和 Solver 资源。
     * 调用此方法后，当前线程不应再使用此 Z3Oracle 实例。
     */
    @Override
    public void close() {
        logger.debug("线程 {} 的 Z3 资源正在清理。", Thread.currentThread().getId());
        // 移除 Solver 和 Context
        threadSolver.remove();
        threadCtx.remove();
        threadVarManager.remove();
        logger.debug("线程 {} 的 Z3 资源已清理。", Thread.currentThread().getId());
    }

    // --- 私有辅助方法 ---

    /**
     * 安全地执行 solver.pop() 并处理潜在的异常。
     * 如果 pop 失败，会尝试重置当前线程的 Solver。
     * @param solver 要操作的 Solver 实例。
     */
    private void popSolver(Solver solver) {
        try {
            solver.pop();
        } catch (Z3Exception e) {
            logger.error("Z3 错误 (pop): {}", e.getMessage());
            // pop 失败可能表示 Solver 状态损坏，尝试重置
            resetSolverForCurrentThread();
        } catch (Exception e) { // 捕获其他可能的运行时异常
            logger.error("执行 pop 时发生意外错误: {}", e.getMessage(), e);
            resetSolverForCurrentThread();
        }
    }

    /**
     * 重置当前线程的 Solver。在 pop 失败或其他需要恢复的情况下调用。
     * 移除旧 Solver，让 ThreadLocal 在下次访问时重新初始化。
     */
    private void resetSolverForCurrentThread() {
        logger.warn("警告: 重置线程 {} 的 Z3 Solver...", Thread.currentThread().getId());
        threadSolver.remove(); // 移除旧的 (可能有问题的) Solver
        // 下次调用 getSolver() 时会重新初始化一个新的 Solver
    }

    /**
     * 统一处理 Z3Exception 的日志记录。
     * @param operation 发生错误的操作名称 (用于日志)。
     * @param e         捕获到的 Z3Exception。
     * @param assertions 当前 push/pop 范围内添加的断言列表 (用于调试)。
     */
    private void handleZ3Exception(String operation, Z3Exception e, List<BoolExpr> assertions) {
        logger.error("!!! Z3 错误 ({})：{}", operation, e.getMessage());
        if (assertions != null && !assertions.isEmpty()) {
            int limit = Math.min(assertions.size(), 5);
            logger.error("  本次查询添加的部分断言 (最多{}条):", limit);
            for (int i = 0; i < limit; i++) {
                try {
                    logger.error("    - {}", assertions.get(i));
                } catch (Exception toStringEx) {
                    logger.error("    - (无法打印断言: {})", toStringEx.getMessage());
                }
            }
        }
    }

    /**
     * 统一处理非 Z3Exception 的通用异常日志记录。
     */
    private void handleGenericException(String operation, Exception e, List<BoolExpr> assertions){
        logger.error("!!! 意外错误 ({}): {} - {}", operation, e.getClass().getSimpleName(), e.getMessage(), e);
        if (assertions != null && !assertions.isEmpty()) {
            logger.error("  (检查相关的断言...)");
        }
    }

    public static class Z3OracleException extends RuntimeException {
        public Z3OracleException(String message, Throwable cause) {
            super(message, cause);
        }
        public Z3OracleException(String message) {
            super(message);
        }
    }
}
