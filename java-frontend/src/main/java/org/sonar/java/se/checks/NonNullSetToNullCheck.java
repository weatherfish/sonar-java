/*
 * SonarQube Java
 * Copyright (C) 2012-2018 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.java.se.checks;

import com.google.common.collect.Lists;
import java.text.MessageFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.sonar.check.Rule;
import org.sonar.java.cfg.CFG;
import org.sonar.java.model.ExpressionUtils;
import org.sonar.java.resolve.JavaSymbol;
import org.sonar.java.resolve.Scope;
import org.sonar.java.se.CheckerContext;
import org.sonar.java.se.ProgramState;
import org.sonar.java.se.constraint.ConstraintManager;
import org.sonar.java.se.constraint.ObjectConstraint;
import org.sonar.java.se.symbolicvalues.SymbolicValue;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.semantic.SymbolMetadata;
import org.sonar.plugins.java.api.tree.Arguments;
import org.sonar.plugins.java.api.tree.AssignmentExpressionTree;
import org.sonar.plugins.java.api.tree.ClassTree;
import org.sonar.plugins.java.api.tree.ExpressionStatementTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.MethodTree;
import org.sonar.plugins.java.api.tree.NewClassTree;
import org.sonar.plugins.java.api.tree.ReturnStatementTree;
import org.sonar.plugins.java.api.tree.StatementTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.VariableTree;

import static org.sonar.java.se.ExplodedGraphWalker.EQUALS_METHODS;
import static org.sonar.java.se.NullableAnnotationUtils.isAnnotatedNullable;
import static org.sonar.java.se.NullableAnnotationUtils.nonNullAnnotation;
import static org.sonar.java.se.NullableAnnotationUtils.parameterNonNullGlobalAnnotation;

@Rule(key = "S2637")
public class NonNullSetToNullCheck extends SECheck {

  private static final String[] JPA_ANNOTATIONS = {
    "javax.persistence.Entity",
    "javax.persistence.Embeddable",
    "javax.persistence.MappedSuperclass"
  };

  private MethodTree methodTree;

  @Override
  public void init(MethodTree tree, CFG cfg) {
    methodTree = tree;
  }

  @Override
  public ProgramState checkPreStatement(CheckerContext context, Tree syntaxNode) {
    AbstractStatementVisitor visitor = new PreStatementVisitor(context);
    syntaxNode.accept(visitor);
    return visitor.programState;
  }

  @Override
  public ProgramState checkPostStatement(CheckerContext context, Tree syntaxNode) {
    AbstractStatementVisitor visitor = new PostStatementVisitor(context);
    syntaxNode.accept(visitor);
    return visitor.programState;
  }

  @Override
  public void checkEndOfExecutionPath(CheckerContext context, ConstraintManager constraintManager) {
    if (methodTree.is(Tree.Kind.CONSTRUCTOR)
      && !isDefaultConstructorForJpa(methodTree)
      && !callsThisConstructor(methodTree)
      && !exitingWithException(context)) {
      ClassTree classTree = (ClassTree) methodTree.parent();
      classTree.members().stream()
        .filter(m -> m.is(Tree.Kind.VARIABLE))
        .map(m -> (VariableTree) m)
        .filter(v -> v.initializer() == null)
        .forEach(v -> checkVariable(context, methodTree, v.symbol()));
    }
  }

  private static boolean exitingWithException(CheckerContext context) {
    return context.getState().peekValue() instanceof SymbolicValue.ExceptionalSymbolicValue;
  }

  private static boolean isDefaultConstructorForJpa(MethodTree methodTree) {
    if (!methodTree.block().body().isEmpty()) {
      // Constructor does something.
      return false;
    }

    SymbolMetadata symbolMetadata = ((ClassTree) methodTree.parent()).symbol().metadata();
    return Stream.of(JPA_ANNOTATIONS).anyMatch(symbolMetadata::isAnnotatedWith);
  }

  private static boolean callsThisConstructor(MethodTree constructor) {
    List<StatementTree> body = constructor.block().body();
    if (body.isEmpty()) {
      return false;
    }
    StatementTree firstStatement = body.get(0);
    if (!firstStatement.is(Tree.Kind.EXPRESSION_STATEMENT)) {
      return false;
    }
    ExpressionTree expression = ((ExpressionStatementTree) firstStatement).expression();
    if (!expression.is(Tree.Kind.METHOD_INVOCATION)) {
      return false;
    }
    ExpressionTree methodSelect = ((MethodInvocationTree) expression).methodSelect();
    return methodSelect.is(Tree.Kind.IDENTIFIER) && "this".equals(((IdentifierTree) methodSelect).name());
  }

  private void checkVariable(CheckerContext context, MethodTree tree, final Symbol symbol) {
    nonNullAnnotation(symbol).ifPresent(annotation -> {
      if (isUndefinedOrNull(context, symbol)) {
        context.reportIssue(tree, this,
          MessageFormat.format("\"{0}\" is marked \"{1}\" but is not initialized in this constructor.", symbol.name(), annotation));
      }
    });
  }

  private static boolean isUndefinedOrNull(CheckerContext context, Symbol symbol) {
    ProgramState programState = context.getState();
    SymbolicValue value = programState.getValue(symbol);
    return value == null;
  }

  private abstract class AbstractStatementVisitor extends CheckerTreeNodeVisitor {

    private final CheckerContext context;

    protected AbstractStatementVisitor(CheckerContext context) {
      super(context.getState());
      this.context = context;
    }

    protected void reportIssue(Tree tree, String message, Object... parameters) {
      context.reportIssue(tree, NonNullSetToNullCheck.this, MessageFormat.format(message, parameters));
    }
  }

  private class PreStatementVisitor extends AbstractStatementVisitor {

    protected PreStatementVisitor(CheckerContext context) {
      super(context);
    }

    @Override
    public void visitAssignmentExpression(AssignmentExpressionTree tree) {
      if (ExpressionUtils.isSimpleAssignment(tree)) {
        IdentifierTree variable = ExpressionUtils.extractIdentifier(tree);
        Symbol symbol = variable.symbol();
        nonNullAnnotation(symbol).ifPresent(annotation -> {
          SymbolicValue assignedValue = programState.peekValue();
          ObjectConstraint constraint = programState.getConstraint(assignedValue, ObjectConstraint.class);
          if (constraint != null && constraint.isNull()) {
            reportIssue(tree, "\"{0}\" is marked \"{1}\" but is set to null.", symbol.name(), annotation);
          }
        });
      }
    }

    @Override
    public void visitNewClass(NewClassTree syntaxTree) {
      Symbol symbol = syntaxTree.constructorSymbol();
      if (symbol.isMethodSymbol()) {
        int peekSize = syntaxTree.arguments().size();
        List<SymbolicValue> argumentValues = Lists.reverse(programState.peekValues(peekSize));
        checkNullArguments(syntaxTree, (JavaSymbol.MethodJavaSymbol) symbol, argumentValues);
      }
    }

    @Override
    public void visitMethodInvocation(MethodInvocationTree syntaxTree) {
      Symbol symbol = syntaxTree.symbol();
      if (symbol.isMethodSymbol()) {
        Arguments arguments = syntaxTree.arguments();
        int peekSize = arguments.size() + 1;
        List<SymbolicValue> argumentValues = Lists.reverse(programState.peekValues(peekSize).subList(0, peekSize - 1));
        ExpressionTree reportTree = syntaxTree.methodSelect();
        if (reportTree.is(Tree.Kind.MEMBER_SELECT)) {
          reportTree = ((MemberSelectExpressionTree) reportTree).identifier();
        }
        checkNullArguments(reportTree, (JavaSymbol.MethodJavaSymbol) symbol, argumentValues);
      }
    }

    private void checkNullArguments(Tree syntaxTree, JavaSymbol.MethodJavaSymbol symbol, List<SymbolicValue> argumentValues) {
      Scope parameters = symbol.getParameters();
      String methodAnnotatedParameterNonNull = parameterNonNullGlobalAnnotation(symbol);
      if (parameters != null) {
        List<JavaSymbol> scopeSymbols = parameters.scopeSymbols();
        int parametersToTest = argumentValues.size();
        if (scopeSymbols.size() < parametersToTest) {
          // The last parameter is a variable length argument: the non-null condition does not apply to its values
          parametersToTest = scopeSymbols.size() - 1;
        }
        for (int i = 0; i < parametersToTest; i++) {
          checkNullArgument(syntaxTree, symbol, scopeSymbols.get(i), argumentValues.get(i), i, methodAnnotatedParameterNonNull);
        }
      }
    }

    private void checkNullArgument(Tree syntaxTree, JavaSymbol.MethodJavaSymbol symbol, JavaSymbol argumentSymbol, SymbolicValue argumentValue, int index,
      @Nullable String methodAnnotatedParameterNonNull) {
      ObjectConstraint constraint = programState.getConstraint(argumentValue, ObjectConstraint.class);
      if (constraint != null && constraint.isNull()) {
        Optional<String> parameterNonNullAnnotation = nonNullAnnotation(argumentSymbol);
        if (parameterNonNullAnnotation.isPresent()) {
          String message = "Parameter {0} to this {1} is marked \"{2}\" but null could be passed.";
          reportIssue(syntaxTree, message, index + 1, (symbol.isConstructor() ? "constructor" : "call"), parameterNonNullAnnotation.get());
        } else if (methodAnnotatedParameterNonNull != null && !parameterIsNullable(symbol, argumentSymbol)) {
          String message = "{0} is marked \"{1}\" but parameter {2} could be null.";
          reportIssue(syntaxTree, message, (symbol.isConstructor() ? "Constructor" : ("\"" + symbol.name() + "\"")), methodAnnotatedParameterNonNull, index + 1);
        }
      }
    }

    private boolean parameterIsNullable(JavaSymbol.MethodJavaSymbol symbol, JavaSymbol argumentSymbol) {
      return isAnnotatedNullable(argumentSymbol) || EQUALS_METHODS.anyMatch(symbol);
    }
  }

  private class PostStatementVisitor extends AbstractStatementVisitor {

    protected PostStatementVisitor(CheckerContext context) {
      super(context);
    }

    @Override
    public void visitReturnStatement(ReturnStatementTree tree) {
      Tree parent = tree.parent();
      while (!parent.is(Tree.Kind.METHOD)) {
        parent = parent.parent();
        if (parent == null) {
          // This occurs when the return statement is within a constructor
          return;
        }
      }
      nonNullAnnotation(((MethodTree) parent).symbol()).ifPresent(annotation -> {
        if (isLocalExpression(tree.expression())) {
          checkReturnedValue(tree, annotation);
        }
      });
    }

    private boolean isLocalExpression(@Nullable ExpressionTree expression) {
      if (expression == null) {
        return false;
      }
      if (expression.is(Tree.Kind.IDENTIFIER)) {
        final Symbol symbol = ((IdentifierTree) expression).symbol().owner();
        return symbol.isMethodSymbol();
      }
      return true;
    }

    private void checkReturnedValue(ReturnStatementTree tree, String nonNullAnnotation) {
      SymbolicValue returnedValue = programState.peekValue();
      ObjectConstraint constraint = programState.getConstraint(returnedValue, ObjectConstraint.class);
      if (constraint != null && constraint.isNull()) {
        reportIssue(tree, "This method''s return value is marked \"{0}\" but null is returned.", nonNullAnnotation);
      }
    }
  }

}
