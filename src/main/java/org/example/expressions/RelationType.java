package org.example.expressions; // 放在 expressions 包下

import java.util.Objects;

public enum RelationType {
    /**
     * 运算符枚举
     */
    LT("<"),    // Less Than
    LE("<="),   // Less Equal
    GT(">"),    // Greater Than
    GE(">=");   // Greater Equal
    // EQ("="), // Equal
    // NEQ("!="); // Not Equal

    private final String symbol;

    RelationType(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }

    /**
     * 返回此关系类型的否定关系。
     * 例如：LT 的否定是 GE。
     */
    public RelationType negate() {
        return switch (this) {
            case LT -> GE;
            case LE -> GT;
            case GT -> LE;
            case GE -> LT;
            // case EQ -> NEQ;
            // case NEQ -> EQ;
        };
    }

    /**
     * 返回此关系类型在交换操作数并取反边界后的等价关系。
     * 例如：(A - B < V) -> (B - A > -V)。
     * (A - B <= V) -> (B - A >= -V)。
     */
    public RelationType flip() {
        return switch (this) {
            case LT -> GT;
            case LE -> GE;
            case GT -> LT;
            case GE -> LE;
            // case EQ -> NEQ;
            // case NEQ -> EQ;
        };
    }
}
