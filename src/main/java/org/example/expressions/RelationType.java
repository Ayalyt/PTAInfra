package org.example.expressions; // 放在 expressions 包下

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static Logger logger = LoggerFactory.getLogger(RelationType.class);

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

    /**
     * 组合两个关系类型，返回更严格的关系。
     * 例如：LT.and(LE) 返回 LT。
     * 仅适用于同向关系（如都是上界或都是下界）。
     * 如果是不同向关系（如 LT 和 GT），则表示矛盾。dcs的设计通过规范化尽可能避免了这一点。
     *
     * @param other 另一个关系类型。
     * @return 组合后的关系类型。
     */
    public RelationType and(RelationType other) {
        // 确保是同向关系
        boolean isThisUpper = (this == LT || this == LE);
        boolean isOtherUpper = (other == LT || other == LE);
        boolean isThisLower = (this == GT || this == GE);
        boolean isOtherLower = (other == GT || other == GE);

        if ((isThisUpper && isOtherLower) || (isThisLower && isOtherUpper)) {
            // 如果 PDBM 严格规范化为只存储上界，则此分支不应被触发。
            // 如果触发，说明逻辑有误或输入不符合规范。
            logger.error("RelationType.and: 尝试组合异向关系：{} 和 {}。这在规范化的 PDBM 中不应发生。", this, other);
            throw new IllegalArgumentException("无法组合异向关系：" + this + " 和 " + other);
        }

        if (isThisUpper) {
            if (this == LT || other == LT) {
                return LT;
            }
            return LE;
        }

        if (isThisLower) {
            if (this == GT || other == GT) {
                return GT;
            }
            return GE;
        }

        logger.error("RelationType.and: 未知关系类型组合：{} 和 {}", this, other);
        throw new IllegalStateException("未知的 RelationType 组合");
    }
}
