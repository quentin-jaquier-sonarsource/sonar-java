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
package org.sonar.java.checks;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.sonar.check.Rule;
import org.sonar.java.cfg.CFG;
import org.sonar.plugins.java.api.IssuableSubscriptionVisitor;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.AssignmentExpressionTree;
import org.sonar.plugins.java.api.tree.BinaryExpressionTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.MethodTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.VariableTree;

@Rule(key = "S12345678")
public class NullDereferenceBeliefStyleCheck extends IssuableSubscriptionVisitor {

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return ImmutableList.of(Tree.Kind.METHOD);
  }

  @Override
  public void visitNode(Tree tree) {
    if (!hasSemantic()) {
      return;
    }
    MethodTree methodTree = (MethodTree) tree;
    if (methodTree.block() == null) {
      return;
    }

    CFG cfg = CFG.build(methodTree);
    NullTracking nullTracking = NullTracking.analyse(cfg);

    for (CFG.Block block : cfg.blocks()) {
      block.elements().forEach(element -> checkElement(element, nullTracking.getOut(block)));
    }
  }

  private void checkElement(Tree element, Set<Symbol> out) {
    if(element.is(Tree.Kind.EQUAL_TO)){
      processEqualTo((BinaryExpressionTree) element, out);
    }
  }

  private void processEqualTo(BinaryExpressionTree element, Set<Symbol> out) {
    if(element.rightOperand().is(Tree.Kind.NULL_LITERAL) && element.leftOperand().is(Tree.Kind.IDENTIFIER)) {
      IdentifierTree id = (IdentifierTree) element.leftOperand();
      if(out.contains(id.symbol())) {
        reportIssue(id, "Null possible");
      }
    }
  }

  private static class NullTracking {
    private final CFG cfg;
    private final Map<CFG.Block, Set<Symbol>> out = new HashMap<>();

    private NullTracking(CFG cfg) {
      this.cfg = cfg;
    }

    private Set<Symbol> getOut(CFG.Block block) {
      return out.get(block);
    }

    private static NullTracking analyse(CFG cfg) {
      NullTracking nullTracking = new NullTracking(cfg);
      //Generate kill/gen for each block in isolation
      Map<CFG.Block, Set<Symbol>> kill = new HashMap<>();
      Map<CFG.Block, Set<Symbol>> gen = new HashMap<>();

      for (CFG.Block block : cfg.blocks()) {
        Set<Symbol> blockKill = new HashSet<>();
        Set<Symbol> blockGen = new HashSet<>();

        nullTracking.processBlockElements(block, blockKill, blockGen);

        kill.put(block, blockKill);
        gen.put(block, blockGen);
      }
      nullTracking.analyzeCFG(kill, gen);

      // Make things immutable.
      for (Map.Entry<CFG.Block, Set<Symbol>> blockSetEntry : nullTracking.out.entrySet()) {
        blockSetEntry.setValue(ImmutableSet.copyOf(blockSetEntry.getValue()));
      }

      return nullTracking;
    }

    //Forward analysis
    private void analyzeCFG(Map<CFG.Block, Set<Symbol>> kill, Map<CFG.Block, Set<Symbol>> gen) {
      Deque<CFG.Block> workList = new LinkedList<>();
      workList.addAll(cfg.blocks());
      while (!workList.isEmpty()) {
        CFG.Block block = workList.removeFirst();

        Set<Symbol> blockIn;

        //Collect all predecessors out set
        List<Set<Symbol>> preds = block.predecessors().stream().map(out::get).filter(Objects::nonNull).collect(Collectors.toList());
        block.exceptions().stream().map(out::get).filter(Objects::nonNull).forEach(preds::add);

        if(!preds.isEmpty()){
          Set<Symbol> newBlockIn = new HashSet<>(preds.get(0));
          for (int i = 1; i < preds.size(); i++){
            newBlockIn = Sets.intersection(newBlockIn, preds.get(i));
          }
          blockIn = new HashSet<>(newBlockIn);
        } else {
          blockIn = new HashSet<>();
        }

        // out = gen and (in - kill)
        Set<Symbol> newOut = new HashSet<>(gen.get(block));
        newOut.addAll(Sets.difference(blockIn, kill.get(block)));

        if (newOut.equals(out.get(block))) {
          continue;
        }
        out.put(block, newOut);
        block.successors().forEach(workList::addLast);
      }
    }

    private void processBlockElements(CFG.Block block, Set<Symbol> blockKill, Set<Symbol> blockGen) {
      // process elements from bottom to top
      for (Tree element : block.elements()) {
        switch (element.kind()) {
          case ASSIGNMENT:
            processAssignment((AssignmentExpressionTree) element, blockKill, blockGen);
            break;
          case MEMBER_SELECT:
            processPointerUse(element, blockGen);
            break;
          case METHOD_INVOCATION:
            processMethodInvocation((MethodInvocationTree) element, blockGen);
            break;
          case VARIABLE:
            processVariable((VariableTree) element, blockKill, blockGen);
            break;
          default:
            // Ignore other kind of elements, no change of gen/kill
        }
      }
    }

    private void processVariable(VariableTree element, Set<Symbol> blockKill, Set<Symbol> blockGen) {
      blockKill.add(element.symbol());
      blockGen.remove(element.symbol());
    }

    private void processMethodInvocation(MethodInvocationTree element, Set<Symbol> blockGen) {
      if(element.methodSelect().is(Tree.Kind.MEMBER_SELECT)) {
        MemberSelectExpressionTree methodSelect = (MemberSelectExpressionTree)element.methodSelect();
        processPointerUse(methodSelect.expression(), blockGen);
      }
    }

    private void processPointerUse(Tree element, Set<Symbol> blockGen) {
      if(element.is(Tree.Kind.IDENTIFIER)) {
        blockGen.add(((IdentifierTree) element).symbol());
      }
    }

    private void processAssignment(AssignmentExpressionTree element, Set<Symbol> blockKill, Set<Symbol> blockGen) {
      ExpressionTree lhs = element.variable();
      if (lhs.is(Tree.Kind.IDENTIFIER)) {
        Symbol symbol = ((IdentifierTree) lhs).symbol();
        //if we see an assignment, we remove all previously used  pointer (we don't know anything for them anymore)
        blockKill.add(symbol);
        blockGen.remove(symbol);
      }
    }
  }
}
