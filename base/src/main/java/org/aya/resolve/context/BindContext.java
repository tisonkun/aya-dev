// Copyright (c) 2020-2023 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.resolve.context;

import kala.collection.mutable.MutableList;
import org.aya.concrete.stmt.Stmt;
import org.aya.ref.LocalVar;
import org.aya.util.error.SourcePos;
import org.aya.util.reporter.Reporter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Introduces a locally bound variable to the context.
 *
 * @author re-xyr
 */
public record BindContext(
  @NotNull Context parent,
  @NotNull String name,
  @NotNull LocalVar ref
) implements Context {
  @Override public @NotNull Context parent() {
    return parent;
  }

  @Override public @NotNull Reporter reporter() {
    return parent.reporter();
  }

  @Override public @NotNull Path underlyingFile() {
    return parent.underlyingFile();
  }

  @Override public MutableList<LocalVar> collect(@NotNull MutableList<LocalVar> container) {
    if (container.noneMatch(v -> Objects.equals(v.name(), ref.name()))) container.append(ref);
    return parent.collect(container);
  }

  @Override public @Nullable ContextUnit.NotExportable getUnqualifiedLocalMaybe(
    @NotNull String name,
    @Nullable Stmt.Accessibility accessibility,
    @NotNull SourcePos sourcePos
  ) {
    if (name.equals(this.name)) return new ContextUnit.NotExportable(ref);
    else return null;
  }

  @Override
  public @Nullable ContextUnit getQualifiedLocalMaybe(
    @NotNull ModulePath.Qualified modName,
    @NotNull String name,
    @Nullable Stmt.Accessibility accessibility,
    @NotNull SourcePos sourcePos
  ) {
    return parent.getQualifiedLocalMaybe(modName, name, accessibility, sourcePos);
  }

  @Override
  public @Nullable ModuleExport getModuleLocalMaybe(@NotNull ModulePath.Qualified modName) {
    return null;
  }
}
