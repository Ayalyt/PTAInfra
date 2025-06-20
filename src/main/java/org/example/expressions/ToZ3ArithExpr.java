package org.example.expressions; // 放在 expressions 包下

import com.microsoft.z3.ArithExpr;
import com.microsoft.z3.Context;
import org.example.symbolic.Z3VariableManager;


public interface ToZ3ArithExpr {

    /**
     * 将此对象转换为 Z3 算术表达式。
     * @param ctx Z3 Context 实例。
     * @param varManager Z3VariableManager 实例，用于管理 Java 变量到 Z3 变量的映射。
     * @return 对应的 Z3 ArithExpr。
     */
    ArithExpr toZ3ArithExpr(Context ctx, Z3VariableManager varManager);
}
