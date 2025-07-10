package org.example.automata.models;

import lombok.Getter;
import org.example.automata.base.Action;
import org.example.automata.base.Alphabet;
import org.example.automata.base.Location;
import org.example.automata.base.Transition;
import org.example.automata.symbolic.SymbolicState;
import org.example.core.Clock;
import org.example.core.Parameter;
import org.example.expressions.dcs.AtomicGuard;
import org.example.expressions.dcs.CPDBM;
import org.example.symbolic.Z3Oracle;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 代表一个参数化时间自动机 (Parametric Timed Automaton, PTA)。
 * PTA 实现了 Automaton 接口，并封装了其所有静态组件。
 * 它将位置的不变量存储在自身内部，而不是在 Location 类中。
 */
@Getter
public final class PTA implements Automaton {

    private final String name;
    private final Set<Location> locations;
    private final Location initialLocation;
    private final Set<Transition> transitions;
    private final Alphabet alphabet;
    private final Set<Clock> clocks;
    private final Set<Parameter> parameters;
    private final Map<Location, Set<AtomicGuard>> invariants; // 将不变量存储在 PTA 中

    /**
     * 构造一个 PTA。
     *
     * @param name            自动机的名称。
     * @param locations       位置集合。
     * @param initialLocation 初始位置。
     * @param transitions     迁移集合。
     * @param alphabet        字母表。
     * @param clocks          时钟集合。
     * @param parameters      参数集合。
     * @param invariants      从位置到其不变量守卫集合的映射。
     */
    public PTA(String name, Set<Location> locations, Location initialLocation, Set<Transition> transitions, Alphabet alphabet, Set<Clock> clocks, Set<Parameter> parameters, Map<Location, Set<AtomicGuard>> invariants) {
        this.name = Objects.requireNonNull(name, "PTA name cannot be null.");
        this.locations = Set.copyOf(Objects.requireNonNull(locations, "Locations set cannot be null."));
        this.initialLocation = Objects.requireNonNull(initialLocation, "Initial location cannot be null.");
        this.transitions = Set.copyOf(Objects.requireNonNull(transitions, "Transitions set cannot be null."));
        this.alphabet = Objects.requireNonNull(alphabet, "Alphabet cannot be null.");
        this.clocks = Set.copyOf(Objects.requireNonNull(clocks, "Clocks set cannot be null."));
        this.parameters = Set.copyOf(Objects.requireNonNull(parameters, "Parameters set cannot be null."));
        this.invariants = Map.copyOf(Objects.requireNonNull(invariants, "Invariants map cannot be null."));
    }

    /**
     * 获取指定位置的不变量。
     * @param location 要查询的位置。
     * @return 该位置的不变量守卫集合，如果未定义则返回空集。
     */
    public Set<AtomicGuard> getInvariantFor(Location location) {
        return this.invariants.getOrDefault(location, Collections.emptySet());
    }

    /**
     * 计算此 PTA 的初始符号化状态集。
     *
     * @param oracle Z3 求解器实例。
     * @return 一组初始的、非空的、规范化的符号化状态。
     */
    public Set<SymbolicState> getInitialSymbolicStates(Z3Oracle oracle) {
        // 1. CPDBM.createInitial 已经完成了 (T, E↑) -> canonical 的过程
        Set<CPDBM> initialCPDBMs = CPDBM.createInitial(this.clocks, oracle);

        // 2. 迭代地将初始位置的不变量 I(q₀) 添加到每个 CPDBM 中
        Set<CPDBM> currentStates = initialCPDBMs;
        for (AtomicGuard invariantGuard : getInvariantFor(this.initialLocation)) {
            Set<CPDBM> nextStates = new HashSet<>();
            for (CPDBM cpdbm : currentStates) {
                // addGuardAndCanonical 已经封装了 add -> canonical 的流程
                nextStates.addAll(cpdbm.addGuardAndCanonical(invariantGuard, oracle));
            }
            currentStates = nextStates;
        }

        // 3. 过滤空状态并创建 SymbolicState
        return currentStates.stream()
                .filter(cpdbm -> !cpdbm.isEmpty(oracle))
                .map(cpdbm -> new SymbolicState(this.initialLocation, cpdbm))
                .collect(Collectors.toSet());
    }

    /**
     * 计算从一个给定的符号化状态出发的所有可能的后继符号化状态。
     *
     * @param currentState 当前的符号化状态。
     * @param oracle       Z3 求解器实例。
     * @return 一组后继的、非空的、规范化的符号化状态。
     */
    public Set<SymbolicState> getSuccessors(SymbolicState currentState, Z3Oracle oracle) {
        Set<SymbolicState> successors = new HashSet<>();
        Location sourceLocation = currentState.getLocation();

        // 找到所有从当前位置出发的迁移
        List<Transition> outgoingTransitions = this.transitions.stream()
                .filter(t -> t.getSource().equals(sourceLocation))
                .toList();

        for (Transition transition : outgoingTransitions) {
            // 0. 从当前状态的 CPDBM 开始
            Set<CPDBM> currentCPDBMs = new HashSet<>();
            currentCPDBMs.add(currentState.getCpdbm());

            // 1. 逐个添加迁移的守卫
            for (AtomicGuard guard : transition.getGuard()) {
                Set<CPDBM> nextCPDBMs = new HashSet<>();
                for (CPDBM cpdbm : currentCPDBMs) {
                    // addGuardAndCanonical 封装了 add -> canonical
                    nextCPDBMs.addAll(cpdbm.addGuardAndCanonical(guard, oracle));
                }
                currentCPDBMs = nextCPDBMs;
            }

            // 2. 应用重置和时间流逝
            Set<CPDBM> afterResetAndDelay = new HashSet<>();
            for (CPDBM cpdbm : currentCPDBMs) {
                // resetAndCanonical 和 delayAndCanonical 封装了操作和规范化
                // 为了效率，可以先 reset, 再 delay, 最后统一 canonical
                CPDBM afterReset = cpdbm.reset(transition.getResetSet());
                CPDBM afterDelay = afterReset.delay();
                afterResetAndDelay.add(afterDelay);
            }

            // 3. 对 reset 和 delay 之后的结果进行规范化
            Set<CPDBM> canonicalizedStates = new HashSet<>();
            for (CPDBM cpdbm : afterResetAndDelay) {
                canonicalizedStates.addAll(cpdbm.canonical(oracle));
            }
            currentCPDBMs = canonicalizedStates;

            // 4. 添加目标位置的不变量
            for (AtomicGuard invariantGuard : getInvariantFor(transition.getTarget())) {
                Set<CPDBM> nextCPDBMs = new HashSet<>();
                for (CPDBM cpdbm : currentCPDBMs) {
                    nextCPDBMs.addAll(cpdbm.addGuardAndCanonical(invariantGuard, oracle));
                }
                currentCPDBMs = nextCPDBMs;
            }

            // 5. 过滤空状态并创建最终的后继 SymbolicState
            for (CPDBM finalCpdbm : currentCPDBMs) {
                if (!finalCpdbm.isEmpty(oracle)) {
                    successors.add(new SymbolicState(transition.getTarget(), finalCpdbm));
                }
            }
        }

        return successors;
    }

    @Override
    public String toString() {
        return "PTA(name='" + name + "', initial=" + initialLocation.getLabel() + ")";
    }
}
