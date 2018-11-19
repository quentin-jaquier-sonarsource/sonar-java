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
package org.sonar.java.checks.security;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.JavaFileScanner;
import org.sonar.plugins.java.api.JavaFileScannerContext;
import org.sonar.plugins.java.api.semantic.Type;
import org.sonar.plugins.java.api.tree.BaseTreeVisitor;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.VariableTree;

@Rule(key = "S5042")
public class ZipEntryCheck extends BaseTreeVisitor implements JavaFileScanner {

  private static final Set<String> ZIP_ENTRY_TYPES = Stream.of(
    "java.util.zip.ZipEntry",
    "org.apache.commons.compress.archivers.ArchiveEntry").collect(Collectors.toSet());

  private JavaFileScannerContext context;

  @Override
  public void scanFile(JavaFileScannerContext context) {
    if (context.getSemanticModel() == null) {
      return;
    }
    this.context = context;
    scan(context.getTree());
  }

  @Override
  public void visitVariable(VariableTree tree) {
    if (tree.initializer() == null && isZipEntryType(tree.symbol().type())) {
      // skip the visit in case of issue already reported on the variable
      context.reportIssue(this, tree, "Make sure that decompressing this archive file is safe here.");
    } else {
      super.visitVariable(tree);
    }
  }

  @Override
  public void visitMethodInvocation(MethodInvocationTree tree) {
    if (isZipEntryType(tree.symbolType())) {
      context.reportIssue(this, tree, "Make sure that decompressing this archive file is safe here.");
    }
    super.visitMethodInvocation(tree);
  }

  private static boolean isZipEntryType(Type type) {
    return ZIP_ENTRY_TYPES.stream().anyMatch(type::isSubtypeOf);
  }

}
