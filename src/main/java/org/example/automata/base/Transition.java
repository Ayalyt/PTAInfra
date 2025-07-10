package org.example.automata.base;

import lombok.Getter;
import org.example.expressions.dcs.AtomicGuard;
import java.util.Objects;
import java.util.Set;

@Getter
public final class Transition {

    private final Location source;
    private final Location target;
    private final Action action;
    private final Set<AtomicGuard> guard;
    private final ResetSet resetSet;

    private final int hashCode;

    /**
     * @param source   源位置 (q)
     * @param target   目标位置 (q')
     * @param action   触发迁移的动作 (a)
     * @param guard    迁移的守卫 (g)
     * @param resetSet 迁移的时钟重置集 (r)
     */
    public Transition(Location source, Location target, Action action, Set<AtomicGuard> guard, ResetSet resetSet) {
        this.source = Objects.requireNonNull(source, "Source location cannot be null.");
        this.target = Objects.requireNonNull(target, "Target location cannot be null.");
        this.action = Objects.requireNonNull(action, "Action cannot be null.");
        this.guard = Objects.requireNonNull(guard, "Guard cannot be null.");
        this.resetSet = Objects.requireNonNull(resetSet, "ResetSet cannot be null.");
        this.hashCode = Objects.hash(source, target, action, guard, resetSet);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Transition that = (Transition) o;
        return source.equals(that.source) &&
                target.equals(that.target) &&
                action.equals(that.action) &&
                guard.equals(that.guard) &&
                resetSet.equals(that.resetSet);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return String.format("%s --[%s, %s, %s]--> %s",
                source.getLabel(),
                action.toString(),
                guard.toString(),
                resetSet.toString(),
                target.getLabel());
    }
}
