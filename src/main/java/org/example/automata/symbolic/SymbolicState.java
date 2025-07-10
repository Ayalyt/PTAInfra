package org.example.automata.symbolic;

import lombok.Getter;
import org.example.automata.base.Location;
import org.example.expressions.dcs.CPDBM;
import java.util.Objects;

/**
 * 代表一个符号化状态，即 (q, C, D)。
 * 这是符号化状态空间图中的一个节点。
 * 此类是不可变的。
 */
@Getter
public final class SymbolicState {

    private final Location location; // q
    private final CPDBM cpdbm;       // (C, D)

    private final int hashCode;

    public SymbolicState(Location location, CPDBM cpdbm) {
        this.location = Objects.requireNonNull(location, "Location cannot be null.");
        this.cpdbm = Objects.requireNonNull(cpdbm, "CPDBM cannot be null.");
        this.hashCode = Objects.hash(location, cpdbm);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SymbolicState that = (SymbolicState) o;
        return location.equals(that.location) &&
                cpdbm.equals(that.cpdbm);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return "SymbolicState(\n  Location: " + location.getLabel() + ",\n  " + cpdbm.toString().indent(2) + "\n)";
    }
}
