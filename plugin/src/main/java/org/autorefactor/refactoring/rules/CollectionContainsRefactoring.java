/*
 * AutoRefactor - Eclipse plugin to automatically refactor Java code bases.
 *
 * Copyright (C) 2015-2016 Jean-Noël Rouvignac - initial API and implementation
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program under LICENSE-GNUGPL.  If not, see
 * <http://www.gnu.org/licenses/>.
 *
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution under LICENSE-ECLIPSE, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.autorefactor.refactoring.rules;

import java.util.List;

import org.autorefactor.refactoring.ASTBuilder;
import org.autorefactor.refactoring.BlockSubVisitor;
import org.autorefactor.refactoring.ForLoopHelper.ForLoopContent;
import org.autorefactor.util.NotImplementedException;
import org.autorefactor.util.Pair;
import org.eclipse.jdt.core.dom.ASTMatcher;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.BreakStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import static org.autorefactor.refactoring.ASTHelper.*;
import static org.autorefactor.refactoring.ForLoopHelper.*;
import static org.autorefactor.refactoring.ForLoopHelper.ContainerType.*;
import static org.autorefactor.refactoring.ForLoopHelper.IterationType.*;
import static org.eclipse.jdt.core.dom.Assignment.Operator.*;

/** See {@link #getDescription()} method. */
public class CollectionContainsRefactoring extends AbstractRefactoringRule {
    @Override
    public String getDescription() {
        return "Replace loop with Collection.contains(Object obj).";
    }

    @Override
    public String getName() {
        return "Collection.contains() rather than loop";
    }

    @Override
    public boolean visit(Block node) {
        final AssignmentForAndReturnVisitor assignmentForAndReturnVisitor =
                new AssignmentForAndReturnVisitor(ctx, node);
        node.accept(assignmentForAndReturnVisitor);
        return assignmentForAndReturnVisitor.getResult();
    }

    private static final class AssignmentForAndReturnVisitor extends BlockSubVisitor {

        public AssignmentForAndReturnVisitor(final RefactoringContext ctx, final Block startNode) {
            super(ctx, startNode);
        }

        @Override
        public boolean visit(EnhancedForStatement node) {
            final Expression iterable = node.getExpression();
            final SingleVariableDeclaration loopVariable = node.getParameter();
            final IfStatement is = uniqueStmtAs(node.getBody(), IfStatement.class);
            return maybeReplaceWithCollectionContains(node, iterable, loopVariable.getName(), is);
        }

        private boolean maybeReplaceWithCollectionContains(
                Statement forNode, Expression iterable, Expression loopElement, IfStatement is) {
            if (is != null
                    && is.getElseStatement() == null
                    && instanceOf(iterable, "java.util.Collection")) {
                MethodInvocation cond = as(is.getExpression(), MethodInvocation.class);
                List<Statement> thenStmts = asList(is.getThenStatement());
                if (!thenStmts.isEmpty()
                        && isMethod(cond, "java.lang.Object", "equals", "java.lang.Object")) {
                    Expression toFind = getExpressionToFind(cond, loopElement);
                    if (toFind != null) {
                        if (thenStmts.size() == 1) {
                            Statement thenStmt = thenStmts.get(0);
                            BooleanLiteral innerBl = getReturnedBooleanLiteral(thenStmt);

                            Statement forNextStmt = getNextStatement(forNode);
                            BooleanLiteral outerBl = getReturnedBooleanLiteral(forNextStmt);

                            Boolean isPositive = signCollectionContains(innerBl, outerBl);
                            if (isPositive != null) {
                                replaceLoopAndReturn(forNode, iterable, toFind, forNextStmt, isPositive);
                                setResult(DO_NOT_VISIT_SUBTREE);
                                return DO_NOT_VISIT_SUBTREE;
                            }
                            return maybeReplaceLoopAndVariable(forNode, iterable, thenStmt, toFind);
                        } else {
                            BreakStatement bs = as(thenStmts.get(thenStmts.size() - 1), BreakStatement.class);
                            if (bs != null && bs.getLabel() == null) {
                                if (thenStmts.size() == 2) {
                                    if (maybeReplaceLoopAndVariable(forNode, iterable, thenStmts.get(0), toFind)
                                            == DO_NOT_VISIT_SUBTREE) {
                                        return DO_NOT_VISIT_SUBTREE;
                                    }
                                }

                                replaceLoopByIf(forNode, iterable, thenStmts, toFind, bs);
                                setResult(DO_NOT_VISIT_SUBTREE);
                                return DO_NOT_VISIT_SUBTREE;
                            }
                        }
                    }
                }
            }
            return VISIT_SUBTREE;
        }

        private void replaceLoopByIf(Statement forNode, Expression iterable, List<Statement> thenStmts,
                Expression toFind, BreakStatement bs) {
            thenStmts.remove(thenStmts.size() - 1);

            ASTBuilder b = getCtx().getASTBuilder();
            Statement replacement = b.if0(collectionContains(iterable, toFind, true, b),
                    b.block(b.copyRange(thenStmts)));
            getCtx().getRefactorings().replace(forNode, replacement);

            thenStmts.add(bs);
        }

        private void replaceLoopAndReturn(Statement forNode, Expression iterable, Expression toFind,
                Statement forNextStmt, boolean negate) {
            ASTBuilder b = getCtx().getASTBuilder();
            getCtx().getRefactorings().replace(forNode,
                    b.return0(
                            collectionContains(iterable, toFind, negate, b)));
            if (forNextStmt.equals(getNextSibling(forNode))) {
                getCtx().getRefactorings().remove(forNextStmt);
            }
        }

        private boolean maybeReplaceLoopAndVariable(
                Statement forNode, Expression iterable, Statement uniqueThenStmt, Expression toFind) {
            Statement previousStmt = getPreviousStatement(forNode);
            if (previousStmt != null) {
                boolean previousStmtIsPreviousSibling = previousStmt.equals(getPreviousSibling(forNode));
                Assignment as = asExpression(uniqueThenStmt, Assignment.class);
                Pair<Name, Expression> innerInit = decomposeInitializer(as);
                Name initName = innerInit.getFirst();
                Expression init2 = innerInit.getSecond();
                Pair<Name, Expression> outerInit = getInitializer(previousStmt);
                if (isSameVariable(outerInit.getFirst(), initName)) {
                    Boolean isPositive = signCollectionContains((BooleanLiteral) init2,
                            (BooleanLiteral) outerInit.getSecond());
                    if (isPositive != null) {
                        replaceLoopAndVariable(forNode, iterable, toFind, previousStmt,
                                previousStmtIsPreviousSibling, initName, isPositive);
                        setResult(DO_NOT_VISIT_SUBTREE);
                        return DO_NOT_VISIT_SUBTREE;
                    }
                }
            }
            return VISIT_SUBTREE;
        }

        private void replaceLoopAndVariable(Statement forNode, Expression iterable, Expression toFind,
                Statement previousStmt, boolean previousStmtIsPreviousSibling, Name initName, boolean isPositive) {
            ASTBuilder b = getCtx().getASTBuilder();
            Statement replacement;
            if (previousStmtIsPreviousSibling
                    && previousStmt instanceof VariableDeclarationStatement) {
                replacement = b.declareStmt(b.type("boolean"), b.move((SimpleName) initName),
                        collectionContains(iterable, toFind, isPositive, b));
            } else if (!previousStmtIsPreviousSibling
                    || previousStmt instanceof ExpressionStatement) {
                replacement = b.toStmt(b.assign(b.copy(initName), ASSIGN,
                        collectionContains(iterable, toFind, isPositive, b)));
            } else {
                throw new NotImplementedException(forNode);
            }
            getCtx().getRefactorings().replace(forNode, replacement);
            if (previousStmtIsPreviousSibling) {
                getCtx().getRefactorings().remove(previousStmt);
            }
        }

        private Expression collectionContains(Expression iterable, Expression toFind, boolean isPositive,
                ASTBuilder b) {
            final MethodInvocation invoke = b.invoke(b.move(iterable), "contains", b.move(toFind));
            if (isPositive) {
                return invoke;
            } else {
                return b.not(invoke);
            }
        }

        private Boolean signCollectionContains(BooleanLiteral innerBl, BooleanLiteral outerBl) {
            if (innerBl != null
                    && outerBl != null
                    && innerBl.booleanValue() != outerBl.booleanValue()) {
                return innerBl.booleanValue();
            }
            return null;
        }

        private Pair<Name, Expression> getInitializer(Statement stmt) {
            if (stmt instanceof VariableDeclarationStatement) {
                return uniqueVariableDeclarationFragmentName(stmt);
            } else if (stmt instanceof ExpressionStatement) {
                Assignment as = asExpression(stmt, Assignment.class);
                if (hasOperator(as, ASSIGN)
                        && as.getLeftHandSide() instanceof Name) {
                    return Pair.of((Name) as.getLeftHandSide(), as.getRightHandSide());
                }
            }
            return Pair.empty();
        }

        private IfStatement uniqueStmtAs(Statement stmt, Class<IfStatement> stmtClazz) {
            return as(uniqueStmt(asList(stmt)), stmtClazz);
        }

        private Statement uniqueStmt(List<Statement> stmts) {
            if (stmts.size() == 1) {
                return stmts.get(0);
            }
            return null;
        }

        private BooleanLiteral getReturnedBooleanLiteral(Statement stmt) {
            ReturnStatement rs = as(stmt, ReturnStatement.class);
            if (rs != null) {
                return as(rs.getExpression(), BooleanLiteral.class);
            }
            return null;
        }

        private Expression getExpressionToFind(MethodInvocation cond, Expression forVar) {
            Expression expr = removeParentheses(cond.getExpression());
            Expression arg0 = removeParentheses(arg0(cond));
            if (isSameVariable(forVar, expr)) {
                return arg0;
            } else if (isSameVariable(forVar, arg0)) {
                return expr;
            } else if (matches(forVar, expr)) {
                return arg0;
            } else if (matches(forVar, arg0)) {
                return expr;
            } else {
                return null;
            }
        }

        private boolean matches(Expression e1, Expression e2) {
            return match(new ASTMatcher(), e1, e2);
        }

        @Override
        public boolean visit(ForStatement node) {
            final ForLoopContent loopContent = iterateOverContainer(node);
            final List<Statement> stmts = asList(node.getBody());
            if (loopContent != null
                    && COLLECTION.equals(loopContent.getContainerType())) {
                if (INDEX.equals(loopContent.getIterationType())) {
                    Expression loopElement = null;
                    IfStatement is;
                    if (stmts.size() == 2) {
                        Pair<Name, Expression> loopVarPair = uniqueVariableDeclarationFragmentName(stmts.get(0));
                        loopElement = loopVarPair.getFirst();
                        MethodInvocation mi = as(loopVarPair.getSecond(), MethodInvocation.class);
                        if (!matches(mi, collectionGet(loopContent))
                                || !isSameVariable(mi.getExpression(), loopContent.getContainerVariable())) {
                            return VISIT_SUBTREE;
                        }

                        is = as(stmts.get(1), IfStatement.class);
                    } else if (stmts.size() == 1) {
                        is = as(stmts.get(0), IfStatement.class);
                        loopElement = collectionGet(loopContent);
                    } else {
                        return VISIT_SUBTREE;
                    }

                    return maybeReplaceWithCollectionContains(node, loopContent.getContainerVariable(), loopElement,
                            is);
                } else if (ITERATOR.equals(loopContent.getIterationType())) {
                    Expression loopElement = null;
                    IfStatement is;
                    if (stmts.size() == 2) {
                        Pair<Name, Expression> loopVarPair = uniqueVariableDeclarationFragmentName(stmts.get(0));
                        loopElement = loopVarPair.getFirst();
                        MethodInvocation mi = as(loopVarPair.getSecond(), MethodInvocation.class);
                        if (!matches(mi, iteratorNext(loopContent))
                                || !isSameVariable(mi.getExpression(), loopContent.getIteratorVariable())) {
                            return VISIT_SUBTREE;
                        }

                        is = as(stmts.get(1), IfStatement.class);
                    } else if (stmts.size() == 1) {
                        is = as(stmts.get(0), IfStatement.class);
                        loopElement = iteratorNext(loopContent);
                    } else {
                        return VISIT_SUBTREE;
                    }

                    return maybeReplaceWithCollectionContains(node, loopContent.getContainerVariable(), loopElement,
                            is);
                }
            }
            return VISIT_SUBTREE;
        }

        private MethodInvocation iteratorNext(final ForLoopContent loopContent) {
            ASTBuilder b = getCtx().getASTBuilder();
            return b.invoke(
                    b.copySubtree(loopContent.getIteratorVariable()),
                    "next");
        }

        private MethodInvocation collectionGet(final ForLoopContent loopContent) {
            ASTBuilder b = getCtx().getASTBuilder();
            return b.invoke(
                    b.copySubtree(loopContent.getContainerVariable()),
                    "get",
                    b.copySubtree(loopContent.getLoopVariable()));
        }

        private Pair<Name, Expression> uniqueVariableDeclarationFragmentName(Statement stmt) {
            VariableDeclarationFragment vdf = getUniqueFragment(as(stmt, VariableDeclarationStatement.class));
            if (vdf != null) {
                return Pair.of((Name) vdf.getName(), vdf.getInitializer());
            }
            return Pair.empty();
        }
    }
}
