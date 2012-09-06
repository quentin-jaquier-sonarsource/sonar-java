/*
 * Sonar Java
 * Copyright (C) 2012 SonarSource
 * dev@sonar.codehaus.org
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
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.java.ast;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.sonar.sslr.api.AstNode;
import com.sonar.sslr.api.AuditListener;
import com.sonar.sslr.api.CommentAnalyser;
import com.sonar.sslr.api.RecognitionException;
import com.sonar.sslr.impl.Parser;
import com.sonar.sslr.impl.ast.AstWalker;
import com.sonar.sslr.squid.SquidAstVisitor;
import org.slf4j.LoggerFactory;
import org.sonar.java.ast.api.JavaGrammar;
import org.sonar.java.ast.api.JavaMetric;
import org.sonar.java.ast.visitors.VisitorContext;
import org.sonar.squid.api.CodeVisitor;
import org.sonar.squid.api.SourceCodeSearchEngine;
import org.sonar.squid.api.SourceCodeTreeDecorator;
import org.sonar.squid.api.SourceProject;
import org.sonar.squid.indexer.SquidIndex;

import java.io.File;
import java.util.Collection;
import java.util.List;

/**
 * Replacement for {@link com.sonar.sslr.squid.AstScanner<JavaGrammar>}.
 */
public class AstScanner {

  private final SquidIndex index = new SquidIndex();
  private final List<SquidAstVisitor<JavaGrammar>> visitors = Lists.newArrayList();
  private final List<AuditListener> auditListeners = Lists.newArrayList();
  private final Parser<JavaGrammar> parser;
  private CommentAnalyser commentAnalyser;

  public AstScanner(Parser<JavaGrammar> parser) {
    this.parser = parser;
  }

  public void scan(Collection<File> files) {
    SourceProject project = new SourceProject("Java Project");
    index.index(project);
    project.setSourceCodeIndexer(index);
    VisitorContext context = new VisitorContext(project);
    context.setGrammar(parser.getGrammar());
    context.setCommentAnalyser(commentAnalyser);

    for (SquidAstVisitor<JavaGrammar> visitor : visitors) {
      visitor.setContext(context);
      visitor.init();
    }

    AstWalker astWalker = new AstWalker(visitors);

    for (File file : files) {
      context.setFile(file);

      try {
        AstNode ast = parser.parse(file);
        astWalker.walkAndVisit(ast);
      } catch (RecognitionException e) {
        LoggerFactory.getLogger(getClass()).error("Unable to parse source file : " + file.getAbsolutePath());

        // TODO support extended stack trace

        // Process the exception
        for (SquidAstVisitor<JavaGrammar> visitor : visitors) {
          visitor.visitFile(null);
        }

        for (AuditListener auditListener : auditListeners) {
          auditListener.processRecognitionException(e);
        }

        for (SquidAstVisitor<JavaGrammar> visitor : Iterables.reverse(visitors)) {
          visitor.leaveFile(null);
        }
      }
    }

    for (SquidAstVisitor<JavaGrammar> visitor : visitors) {
      visitor.destroy();
    }

    SourceCodeTreeDecorator decorator = new SourceCodeTreeDecorator(project);
    decorator.decorateWith(JavaMetric.values());
    decorator.decorateWith(org.sonar.squid.measures.Metric.values());
  }

  public void withSquidAstVisitor(SquidAstVisitor<JavaGrammar> visitor) {
    if (visitor instanceof AuditListener) {
      auditListeners.add((AuditListener) visitor);
    }
    this.visitors.add(visitor);
  }

  public SourceCodeSearchEngine getIndex() {
    return index;
  }

  public void setCommentAnalyser(CommentAnalyser commentAnalyser) {
    this.commentAnalyser = commentAnalyser;
  }

  public void accept(CodeVisitor visitor) {
    if (visitor instanceof SquidAstVisitor) {
      withSquidAstVisitor((SquidAstVisitor<JavaGrammar>) visitor);
    }
  }

}
